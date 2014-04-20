(ns com.palletops.cache-resources
  "Caches for resources that need closing in some way"
  (:require
   [com.palletops.cache-resources.protocols :as impl])
  (:import
   clojure.lang.MapEntry))

(defn release
  "Release from `cache` the value associated with `e` if it exists.
  Returns a tuple containing the cache without the element `e`, and
  the value associated with `e`, or `default` if supplied, or nil
  otherwise.  Does not call any resource function on the element."
  ([cache e] (impl/release cache e))
  ([cache e default] (impl/release cache e)))

(defn atomic-release
  "Release from the cache in the atom `cache` the value associated
  with `e` if it exists.  Returns the value associated with `e`, or
  `default` if supplied, or nil otherwise.  Does not call any resource
  function on the element."
  ([cache e default]
     (let [state-atom (atom nil)]
       (swap! cache
              (fn [cache]
                (let [[cache v] (release cache e default)]
                  (reset! state-atom v)
                  cache)))
       @state-atom))
  ([cache e]
     (atomic-release cache e nil)))

;; based on core.cache
(defmacro defcache
  "Macro to define a chache type.

  This is a wrapper for deftype, with the same signature, that adds
  implementation of the clojure map interfaces.

  The first field is expected to be something that implements Counter,
  IPersistentCollection and Seqable."
  [cache-name [& fields] & opts+specs]
  (let [base-collection (first fields)]
    `(deftype ~cache-name [~@fields]
       ~@opts+specs

       clojure.lang.ILookup
       (valAt [this# key#]
         (impl/lookup this# key#))
       (valAt [this# key# not-found#]
         (if (impl/has? this# key#)
           (impl/lookup this# key#)
           not-found#))

       clojure.lang.IPersistentMap
       (assoc [this# k# v#]
         (impl/miss this# k# v#))
       (without [this# k#]
         (impl/evict this# k#))

       clojure.lang.Associative
       (containsKey [this# k#]
         (impl/has? this# k#))
       (entryAt [this# k#]
         (if (impl/has? this# k#)
           (MapEntry. k# (impl/lookup this# k#))))

       clojure.lang.Counted
       (count [this#]
         (clojure.core/count ~base-collection))

       clojure.lang.IPersistentCollection
       (empty [this#]
         (impl/seed this# (empty ~base-collection)))
       (equiv [_# other#]
         (clojure.lang.Util/equiv ~base-collection other#))

       clojure.lang.Seqable
       (seq [_#]
         (seq ~base-collection)))))

;;; # FIFO cache
(defn- dissoc-keys
  "dissoc a sequence of keys, ks, from a map, m."
  [m ks]
  (if (seq ks)
    (recur (dissoc m (first ks)) (rest ks))
    m))

(defn- fifo-build-cache
  "Build a cache from the cache map, m, limiting to limit elements."
  [m limit]
  (let [ks (keys m)
        ;; split-at will return an empty first seq if passed a
        ;; negative index
        [to-dissoc to-keep] (split-at (- (count ks) limit) ks)]
    {:cache (dissoc-keys m to-dissoc)
     :queue (into clojure.lang.PersistentQueue/EMPTY to-keep)}))

(defn- fifo-shrink-cache
  "Shrink the cache ao a new element may be inserted without exceeding
  limit."
  [cache queue limit]
  (let [k (peek queue)]
    [(dissoc cache k) (pop queue) (get cache k)]))


(defn- fifo-remove-queue
  [items queue]
  {:pre [(set? items)]}
  (into clojure.lang.PersistentQueue/EMPTY
        (remove items queue)))

(declare fifo-miss fifo-evict fifo-release)

(defcache FIFOCache [cache queue limit evict-f]
  impl/CacheImpl
  (lookup [_ item]
    (get cache item))
  (lookup [_ item default]
    (get cache item default))
  (has? [_ item]
    (contains? cache item))

  impl/Cache
  (hit [this item]
    this)

  (miss [_ item result]
    (fifo-miss cache queue limit evict-f item result))

  (evict [this item]
    (fifo-evict this cache queue limit evict-f item))

  (seed [_ base]
    (let [{:keys [cache queue]} (fifo-build-cache base limit)]
      (FIFOCache. cache queue limit evict-f)))

  impl/ReleasableCache
  (release [this item]
    (fifo-release this cache queue limit evict-f item))

  (release [this item default]
    (fifo-release this cache queue limit evict-f item default))

  Object
  (toString [_]
    (str cache ", " (pr-str (seq queue)))))

(defn- fifo-miss
  [cache queue limit evict-f item result]
  {:pre [(= (count cache) (count queue))]
   :post [(= (count (.cache %)) (count (.queue %)))]}
  (let [v (get cache item ::miss)]
    (if (= ::miss v)
      (let [[c q v] (if (>= (count cache) limit)
                      (fifo-shrink-cache cache queue limit)
                      [cache queue])]
        (when (and evict-f v)
          (evict-f v))
        (FIFOCache. (assoc c item result) (conj q item) limit evict-f))
      (FIFOCache.
       (assoc cache item result)
       (conj (fifo-remove-queue #{item} queue) item)
       limit evict-f))))

(defn- fifo-evict
  "Evict an item from the fifo cache, returning a new fifo"
  [this cache queue limit evict-f item]
  {:pre [(= (count cache) (count queue))]
   :post [(= (count (.cache %)) (count (.queue %)))]}
  (let [v (get cache item ::miss)]
    (if (= ::miss v)
      this
      (do
        (when (and evict-f v)
          (evict-f v))
        (FIFOCache.
         (dissoc cache item)
         (fifo-remove-queue #{item} queue)
         limit
         evict-f)))))

(defn- fifo-release
  "Release an item from the fifo cache, returning a vector tuple with
  the new fifo and the removed element.  Any `:evict-f` is not called
  on the element"
  ([this cache queue limit evict-f item default]
     {:pre [(= (count cache) (count queue))]
      :post [(= (count (.cache (first %))) (count (.queue (first %))))]}
     (let [v (get cache item ::miss)]
       (if (= ::miss v)
         [this default]
         [(FIFOCache.
           (dissoc cache item)
           (fifo-remove-queue #{item} queue)
           limit
           evict-f)
          v])))
  ([this cache queue limit evict-f item]
     (fifo-release this cache queue limit evict-f item nil)))

(defn fifo-cache-factory
  "Returns a FIFO cache with the cache and FIFO queue initialized to
   `base` -- the queue is filled as the values are pulled out of
   `base`.  If the associative structure can guarantee ordering, then
   the said ordering will define the eventual eviction order.
   Otherwise, there are no guarantees for the eventual eviction
   ordering.

   The options takes an optional `:threshold` argument that defines
   the maximum number of elements in the cache before the FIFO
   semantics apply (default is 32).

   If the number of elements in `base` is greater than the limit then
   some items in `base` will be dropped from the resulting cache.  If
   the associative structure used as `base` can guarantee sorting,
   then the last `limit` elements will be used as the cache seed
   values.  Otherwise, there are no guarantees about the elements in
   the resulting cache.

   The `:evict-f` may be used to specify a function that will be
   called on a value from cache when it leaves the cache.  The
   `:evict-f` function is not called if you dissoc the value
   explicitly."
  [base {:keys [evict-f threshold] :or {threshold 32} :as options}]
  {:pre [(number? threshold) (pos? threshold)
         (map? base)]
   :post [(== (count base) (count (.queue ^FIFOCache %)))
          (= (count (.cache %)) (count (.queue %)))]}
  (impl/seed
   (FIFOCache. {} clojure.lang.PersistentQueue/EMPTY threshold evict-f)
   base))

;; Local Variables:
;; mode: clojure
;; eval: (put 'defcache 'clojure-backtracking-indent '(4 4 (2)))
;; End:

(ns com.palletops.cache-resources.protocols
  "Cache protocols")

(defprotocol CacheImpl
  "Cache implementation interface."
  (lookup [cache e] [cache e default]
    "Retrieve the value associated with `e` if it exists")
  (has? [cache e]
    "Checks if the cache contains a value associtaed with `e`"))

(defprotocol Cache
  "Cache interface."
  (miss [cache e ret]
    "Is meant to be called if the cache is determined to not contain a
     value associated with `e`")
  (hit [cache e]
    "Is meant to be called if the cache is determined to contain a
    value associated with `e`")
  (evict [cache e]
    "Evict a key from the cache")
  (seed [cache base]
    "Is used to signal that the cache should be created with a seed.
    The contract is that said cache should return an instance of its
    own type."))

(defprotocol ReleasableCache
  "Cache interface for releasing an item from the cache."
  (release [cache e] [cache e default]
    "Release the value associated with `e` if it exists.  Returns the
    value associated with e, or `default` if supplied, or nil
    otherwise.  Does not call any resource function on the element."))

;; Tests from core.cache are:

;; Copyright (c) Rich Hickey. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns com.palletops.cache-resources-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.cache-resources :refer :all])
  (:import
   com.palletops.cache_resources.FIFOCache))

(defn do-dot-lookup-tests [c]
  (are [expect actual] (= expect actual)
       1   (.lookup c :a)
       2   (.lookup c :b)
       42  (.lookup c :c 42)
       nil (.lookup c :c)))

(defn do-ilookup-tests [c]
  (are [expect actual] (= expect actual)
       1   (:a c)
       2   (:b c)
       42  (:X c 42)
       nil (:X c)))

(defn do-assoc [c]
  (are [expect actual] (= expect actual)
       1   (:a (assoc c :a 1))
       nil (:a (assoc c :b 1))))

(defn do-dissoc [c]
  (are [expect actual] (= expect actual)
       2   (:b (dissoc c :a))
       nil (:a (dissoc c :a))
       nil (:b (-> c (dissoc :a) (dissoc :b)))
       0   (count (-> c (dissoc :a) (dissoc :b)))))

(defn do-getting [c]
  (are [actual expect] (= expect actual)
       (get c :a) 1
       (get c :e) nil
       (get c :e 0) 0
       (get c :b 0) 2
       (get c :f 0) nil

       (get-in c [:c :e]) 4
       (get-in c '(:c :e)) 4
       (get-in c [:c :x]) nil
       (get-in c [:f]) nil
       (get-in c [:g]) false
       (get-in c [:h]) nil
       (get-in c []) c
       (get-in c nil) c

       (get-in c [:c :e] 0) 4
       (get-in c '(:c :e) 0) 4
       (get-in c [:c :x] 0) 0
       (get-in c [:b] 0) 2
       (get-in c [:f] 0) nil
       (get-in c [:g] 0) false
       (get-in c [:h] 0) 0
       (get-in c [:x :y] {:y 1}) {:y 1}
       (get-in c [] 0) c
       (get-in c nil 0) c))

(defn do-finding [c]
  (are [expect actual] (= expect actual)
       (find c :a) [:a 1]
       (find c :b) [:b 2]
       (find c :c) nil
       (find c nil) nil))

(defn do-contains [c]
  (are [expect actual] (= expect actual)
       (contains? c :a) true
       (contains? c :b) true
       (contains? c :c) false
       (contains? c nil) false))

(def big-map {:a 1, :b 2, :c {:d 3, :e 4}, :f nil, :g false, nil {:h 5}})
(def small-map {:a 1 :b 2})

(deftest test-fifo-cache-ilookup
  (testing "that the FifoCache can lookup via keywords"
    (do-ilookup-tests
     (FIFOCache. small-map clojure.lang.PersistentQueue/EMPTY 2 nil)))
  (testing "that the FifoCache can lookup via keywords"
    (do-dot-lookup-tests
     (FIFOCache. small-map clojure.lang.PersistentQueue/EMPTY 2 nil)))
  (testing "assoc and dissoc for FifoCache"
    (do-assoc
     (FIFOCache. {} clojure.lang.PersistentQueue/EMPTY 2 nil))
    (do-dissoc
     (FIFOCache.
      {:a 1 :b 2}
      (into clojure.lang.PersistentQueue/EMPTY [:a :b])
      2 nil)))
  (testing "that get and cascading gets work for FifoCache"
    (do-getting
     (FIFOCache. big-map clojure.lang.PersistentQueue/EMPTY 2 nil)))
  (testing "that finding works for FifoCache"
    (do-finding
     (FIFOCache. small-map clojure.lang.PersistentQueue/EMPTY 2 nil)))
  (testing "that contains? works for FifoCache"
    (do-contains
     (FIFOCache. small-map clojure.lang.PersistentQueue/EMPTY 2 nil)))
  (testing
      "that FIFO caches starting with less elements than the threshold work"
    (let [C (fifo-cache-factory (sorted-map :a 1, :b 2) {:threshold 3})]
      (are [x y] (= x y)
           {:a 1, :b 2, :c 3} (.cache (assoc C :c 3))
           {:d 4, :b 2, :c 3} (.cache (assoc C :c 3 :d 4))))))

(deftest test-fifo-cache-with-evict-f
  (testing "dissoc"
    (let [a (atom 0)
          cache (fifo-cache-factory
                 (sorted-map :a 1, :b 2)
                 {:threshold 3 :evict-f #(reset! a %)})
          b (assoc cache :c 3)]
      (is (= 0 @a))
      (let [c (assoc b :d 4)]
        (is (= 1 @a))
        (dissoc c :d)
        (is (= 4 @a) ":evict-f is called"))))
  (testing "release"
    (let [a (atom 0)
          cache (fifo-cache-factory
                 (sorted-map :a 1, :b 2)
                 {:threshold 3 :evict-f #(reset! a %)})
          b (assoc cache :c 3)]
      (is (= 0 @a))
      (let [c (assoc b :d 4)]
        (is (= 1 @a))
        (is (= 4 (second (release c :d))))
        (is (= 1 @a) ":evict-f is not called"))))
  (testing "assoc on element already in cache"
    (let [a (atom 0)
          cache (fifo-cache-factory
                 (sorted-map :a 1, :b 2)
                 {:threshold 3 :evict-f #(reset! a %)})
          b (assoc cache :b 3)]
      (is (dissoc b :b)))))

(deftest atomic-release-test
  (let [a (atom nil)
        cache (atom (fifo-cache-factory
                     (sorted-map :a 1, :b 2)
                     {:threshold 3 :evict-f #(reset! a %)}))]
    (is (= 1 (get @cache :a)))
    (is (= 2 (get @cache :b)))
    (is (= 2 (atomic-release cache :b)))
    (is (nil? @a))
    (is (= ::miss (get @cache :b ::miss)))
    (is (= 1 (get @cache :a)))))

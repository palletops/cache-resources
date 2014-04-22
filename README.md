# cache-resources

A Clojure library to provide caching for resources that need closing.

Caches are immutable and implement Clojure's map interfaces for lookup
in the cache (and adding/removing items to/from the cache).

The elements in the cache can be inspected using `seq` and counted
with `count`.  The queue can be emptied by calling `empty`.

The library provides an underlying CacheImplProtocol to provide a
framework for implementing caches, similar to [core.cache].

## Installation

Add the library to your dependencies:

```clj
:dependencies [[com.palletops/cache-resources "0.1.0"]]
```

## Usage

The library currently only provides a FIFO cache.  Use the `:evict-f`
option key to specify a function of a single argument that will be
called on each value that leaves the cache.

The `:evict-f` function is not called if you `release` an item from
the cache.

Note that `:evict-f` is a side effect on a immutable datastructure,
which means that you should forget the old value of the cache when
inserting items into the cache.

The `:threshold` option key is used to specify the size of the cache
(defaults to 32).

```clj
(require '[com.palletops.cache-resources :refer [fifo-cache-factory]])
(let [cache (fifo-cache-factory
               (sorted-map :a 1, :b 2)
               {:threshold 3 :evict-f #(println % "removed")})
        b (assoc cache :c 3)]
    (let [c (assoc b :d 4)]
      (dissoc c :d)))
```

## See Also

If you don't need the features of cache-resources you may wish to use
[core.cache][core.cache], on which this library is based.

## License

Copyright © 20.1.014 Hugo Duncan

Distributed under the Eclipse Public License either version 1.0.1.0 or (at
your option) any later version.

Contains code from [core.cache][core.cache] that is under the following license:

Copyright (c) Rich Hickey, Michael Fogus and contributors, 20.1.012. All
rights reserved. The use and distribution terms for this software are
covered by the Eclipse Public License 1.0.1.0
(http://opensource.org/licenses/eclipse-1.0.1.0.php) which can be found in
the file epl-v10.1.0.html at the root of this distribution. By using this
software in any fashion, you are agreeing to be bound bythe terms of
this license. You must not remove this notice, or any other, from this
software.

[core.cache]: https://github.com/clojure/core.cache "core.cache"

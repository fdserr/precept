(ns precept.test-helpers
  (:require [precept.core :as core]
            [precept.util :as util]
            [precept.listeners :as l]
            [precept.rules :refer [session]]
            [precept.schema :as schema]
            [precept.spec.test :as test]
            [precept.schema-fixture :refer [test-schema]]
            [clara.rules :as cr]))

(defn rand-str [] (rand-nth ["foo" "bar" "baz" "quux"]))

(defn mk-fact-fn
  "Returns fn that will generate n facts with a when called with eid"
  [n a]
  (fn [e] (repeatedly n #(util/vec->record [e a 42]))))

(defn mk-facts [n eids]
  (let [mk-one-to-one (mk-fact-fn n ::test/one-to-one)
        mk-unique (mk-fact-fn n ::test/unique-identity)
        mk-one-to-many(mk-fact-fn n ::test/one-to-many)]
    (into [] (flatten (mapcat (juxt mk-one-to-one mk-unique mk-one-to-many) eids)))))

(defn max-fid-fact
  ([facts a]
   (apply max-key :t (filter #(= (:a %) a) facts)))
  ([facts e a]
   (apply max-key :t (filter #(and (= (:a %) a) (= (:e %) e)) facts))))

(defn create-test-session
  "Creates a session with `n-facts` of each type of fact supported by schema.
  Derives ancestry from test-schema. Adds fact listener to session. Returns session."
  [facts sources]
  (let [session @(session core-test-session
                   sources
                   :db-schema test-schema)]
    (-> session
      (l/replace-listener)
      (util/insert facts)
      (cr/fire-rules))))
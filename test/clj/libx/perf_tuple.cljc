(ns libx.perf-tuple
    (:require [libx.util :refer [guid
                                 insert
                                 insert!
                                 insert-unconditional!
                                 retract!] :as util]
              [libx.schema-fixture :refer [test-schema]]
              [libx.state :as state]
              [libx.schema :as schema]
              [clara.rules :as cr]
              [clara.rules.accumulators :as acc]
              [libx.spec.sub :as sub]
              [libx.tuplerules :refer [store-action
                                       def-tuple-session
                                       deflogical
                                       def-tuple-rule
                                       def-tuple-query]]
              [libx.listeners :as l]
              [libx.schema :as schema]
      #?(:clj
              [clara.tools.inspect :as inspect])
        [clara.tools.inspect :as inspect]))

(defn trace [& x]
  (comment (apply prn x)))

;; TODO. create fn to reset `rules` atom. As we've discovered this might even be nice to
;; have for non-generated rule names, because when we delete a rule or rename it, it's still in
;; the REPL and requires a restart or manual ns-unmap to clear. We could expose a function
;; that takes all nses in which they are rules and unmaps everything in them.
(deflogical [?e :todo/visible :tag] :- [[_ :ui/visibility-filter :all]]
                                       [[?e :todo/title]])

(store-action :add-todo-action-2)

(cr/defrule add-item-handler
  ;; Works with maps
  ;[:add-todo-action [{title :title}] (= ?title title)]
  [:add-todo-action (= ?title (:title this))]
  ;; Does not work with maps
  ;[:add-todo-action (= ?title title)]
  ;[:add-todo-action (= ?title :title)]
  =>
  (trace "Inserting :todo/title")
  (insert-unconditional! [(guid) :todo/title ?title]))

(def-tuple-rule todo-is-visile-when-filter-is-done-and-todo-done
  [[_ :ui/visibility-filter :done]]
  [[?e :todo/done]]
  =>
  (insert! [?e :todo/visible :tag]))

(def-tuple-rule todo-is-visible-when-filter-active-and-todo-not-done
  [[_ :ui/visibility-filter :active]]
  [[?e :todo/title]]
  [:not [?e :todo/done]]
  =>
  (insert! [?e :todo/visible :tag]))

(def-tuple-rule toggle-all-complete
  [:exists [:ui/toggle-complete]]
  [[?e :todo/title]]
  [:not [?e :todo/done]]
  =>
  (insert-unconditional! [?e :todo/done :tag]))

(def-tuple-rule acc-all-visible
  {:group :report}
  [?count <- (acc/count) :from [:todo/title]]
  [:test (> ?count 0)]
  =>
  (trace "Reporting count" ?count)
  (insert! [-1 :todo/count ?count]))


(def-tuple-rule action-cleanup
  {:group :cleanup}
  [?actions <- (acc/all) :from [:action]]
  [:test (> (count ?actions) 0)]
  =>
  (trace "CLEANING actions" ?actions)
  (doseq [action ?actions]
    (cr/retract! action)))

;; 234 ms
;(cr/defrule remove-older-one-to-one-facts
;  {:super true :salience 100}
;  [?fact1 <- :done-count (= ?e (:e this))]
;  [?fact2 <- :done-count (> (:t ?fact1) (:t this)) (= ?e (:e this))]
;  ;[:test (and (not= ?fact1 ?fact2) (> (:t ?fact1) (:t ?fact2)))]
;  =>
;  (trace (str "SCHEMA MAINT - :one-to-one retracting") ?fact2)
;  (retract! ?fact2))

;; 5486ms
;(cr/defrule remove-older-one-to-one-facts
;  {:super true :salience 100}
;  [?fact1 <- :todo/title (= ?e (:e this))]
;  [?fact2 <- :todo/title (> (:t ?fact1) (:t this)) (= ?e (:e this))]
;  [:test (and (not= ?fact1 ?fact2))]
;  =>
;  (trace (str "SCHEMA MAINT - :one-to-one retracting") ?fact2)
;  (retract! ?fact2))

;; 332ms
;(cr/defrule remove-older-one-to-one-facts
;  {:super true :salience 100}
;  [?fact <- :todo/title (= ?e (:e this))]
;  =>
;  (trace (str "SCHEMA MAINT - :one-to-one retracting") ?fact))

;; 260ms
;(cr/defrule remove-older-one-to-one-facts
;  {:super true :salience 100}
;  [?fact1 <- :todo/title (= ?e (:e this))]
;  [?fact2 <- :todo/title (= 42 (:e this))]
;  =>
;  (trace (str "SCHEMA MAINT - :one-to-one retracting") ?fact1))

;; breaks
;(cr/defrule remove-older-one-to-one-facts
;  {:super true :salience 100}
;  [?fact1 <- :todo/title (= ?e (:e this))]
;  [?fact2 <- :todo/title (= (:e ?fact1) (:e this))]
;  =>
;  (trace (str "SCHEMA MAINT - :one-to-one retracting") ?fact1))

;; 7792ms
;(cr/defrule remove-older-one-to-one-facts
;  {:super true :salience 100}
;  [?fact1 <- :todo/title (= ?e (:e this))]
;  [?fact2 <- :todo/title (= ?e (:e this))]
;  =>
;  (trace (str "SCHEMA MAINT - :one-to-one retracting") ?fact1))

;; 6623ms
;(cr/defrule remove-older-one-to-one-facts
;  {:super true :salience 100}
;  [?fact1 <- :one-to-one (= ?e (:e this))]
;  [?fact2 <- :todo/title (= ?e (:e this)) (> (:t ?fact1) (:t this))]
;  [:test (and (not= ?fact1 ?fact2))]
;  =>
;  (trace (str "SCHEMA MAINT - :one-to-one retracting") ?fact1))


;; 194ms
;(ns-unmap *ns* 'remove-older-one-to-one-facts)

(def groups [:action :calc :report :cleanup])
(def activation-group-fn (util/make-activation-group-fn :calc))
(def activation-group-sort-fn (util/make-activation-group-sort-fn groups :calc))
(def hierarchy (schema/schema->hierarchy test-schema))
(def ancestors-fn (util/make-ancestors-fn hierarchy))

(def-tuple-session tuple-session
  'libx.perf-tuple
  :ancestors-fn ancestors-fn
  :activation-group-fn activation-group-fn
  :activation-group-sort-fn activation-group-sort-fn)

(defn n-facts-session [n]
  (-> tuple-session
    ;(l/replace-listener)
    (insert (repeatedly n #(vector (guid) :todo/title "foobar")))))

(def session (atom (n-facts-session 100000#_0000)))

;(get-in (inspect/inspect @session) [:rule-matches remove-older-one-to-one-facts])
;; 246ms without, 5000ms with

(defn perf-loop [iters]
  (time
    (dotimes [n iters]
      (time
        (reset! session
          (-> @session
            ;(l/replace-listener)
            (util/insert-action [(guid) :add-todo-action-2 {:todo/title "ho"}])
            (util/insert-action [(guid) :add-todo-action {:title "hey"}])
            (insert [[1 :done-count 5]
                     [1 :done-count 6]])
            (cr/fire-rules)))))))

(perf-loop 100)

(l/vec-ops @session)
;(inspect/inspect @session)
;(inspect/explain-activations @session)
;; agenda phases
;; schema maintenance should be high salience and always available
;; action
;; compute
;; report
;; cleanup


;; Timings - all our stuff
;; 100,000 facts
;; 100 iterations
;;
;; ~~~~~~~~~~~~~~~~~~NO AGENDA GROUPS~~~~~~~~~~~~~~~~~~~~~~~~~~
;; ~~~~~~~~~~~~~~~~~~add-item-cleanup NOT FIRING~~~~~~~~~~~~~~~
;; ~~~~No queries~~~~
;; loop                 47ms
;; loading file       1776ms
;;
;; ~~~~Including queries in the session def~~~~
;; qav-
;;     loop              47ms
;;     loading file    2086ms
;;
;; entity-
;;     loop            1384ms
;;     loading file    3330ms
;;
;; ~~~~Tracing, new one every loop, no queries~~~~
;; loop                  74ms
;; loading file          22ms (wtf?)
;;
;; ~~~~~~~~~~~~~~~~~~AGENDA GROUPS~~~~~~~~~~~~~~~~~~~~~~~~~~
;; ~~~~~~~~~~~~~~~~~~add-item-cleanup NOT FIRING~~~~~~~~~~~~~~~
;; ~~~~No queries~~~~
;; loop                 42ms
;; loading file       4359ms
;;
;; ~~~~~~~~~~~~~~~~~~add-item-cleanup FIRING~~~~~~~~~~~~~~~
;; ~~~~No queries~~~~
;; loop                 70ms
;; loading file       4446ms
;;
;;
;;*********************************************************
;;*********************************************************
;; ~~~~~~~~~~~~~~~~~~~~~~ CLJS ~~~~~~~~~~~~~~~~~~~~~~~~~~~

;; ~~~~~~~~~~~~~~~~~~AGENDA GROUPS~~~~~~~~~~~~~~~~~~~~~~~~~~
;; ~~~~~~~~~~~~~~~~~~add-item-cleanup NOT FIRING~~~~~~~~~~~~~~~
;; ~~~~No queries~~~~
;; loop                137ms
;; loading file      24388ms (bc :cache false in cljsbuild?)
;
;; ~~~~~~~~~~~~~~~~~~add-item-cleanup FIRING~~~~~~~~~~~~~~~
;; ~~~~No queries~~~~
;; loop                185ms
;; loading file      21199ms (bc :cache false in cljsbuild?)

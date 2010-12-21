(ns clojureql.predicates
  (:use clojureql.internal
        [clojure.string :only [join] :rename {join join-str}]))

(defn sanitize [expression]
  (reduce #(conj %1 %2) [] (remove keyword? expression)))

(defn parameterize [expression]
  (map #(if (keyword? %) (str (to-tablename %)) "?") expression))

(declare predicate)

(defprotocol Predicate
  (sql-or     [this exprs]           "Compiles to (expr OR expr)")
  (sql-and    [this exprs]           "Compiles to (expr AND expr)")
  (sql-not    [this exprs]           "Compiles to NOT(exprs)")
  (spec-op    [this expr]            "Compiles a special, ie. non infix operation")
  (infix      [this op exprs]        "Compiles an infix operation")
  (prefix     [this op field exprs]  "Compiles a prefix operation"))

(defrecord APredicate [stmt env]
  Object
  (toString [this] (apply str stmt))
  Predicate
  (sql-or    [this exprs]
    (if (empty? (-> exprs first :stmt))
      (assoc this
        :stmt (map str exprs)
        :env  (mapcat :env exprs))
      (assoc this
        :stmt (conj stmt (str "(" (join-str " OR " exprs) ")"))
        :env  (into env (mapcat :env exprs)))))
  (sql-and   [this exprs]
    (if (empty? (-> exprs first :stmt))
      (assoc this
        :stmt (map str exprs)
        :env  (mapcat :env exprs))
      (assoc this
        :stmt (conj stmt (str "(" (join-str " AND " exprs) ")"))
        :env  (into env (mapcat :env exprs)))))
  (sql-not   [this expr]
    (if (empty? (-> expr first :stmt))
      (assoc this
        :stmt (map str expr)
        :env  (mapcat :env expr))
      (assoc this
        :stmt (conj stmt (str "NOT(" (join-str expr) ")"))
        :env  (into env (mapcat :env expr)))))
  (spec-op [this expr]
    (let [[op p1 p2] expr]
      (cond
       (every? nil? (rest expr))
       (assoc this
         :stmt (conj stmt "(NULL " op " NULL)")
         :env  env)
       (nil? p1)
       (.spec-op this [op p2 p1])
       (nil? p2)
       (assoc this
         :stmt (conj stmt (str "(" (name p1) " " op " NULL)"))
         :env [])
       :else
       (infix this "=" (rest expr)))))
  (infix [this op expr]
    (assoc this
      :stmt (conj stmt (format "(%s)"
                         (join-str (format " %s " (upper-name op))
                                   (parameterize expr))))
      :env  (into env (sanitize expr))))
  (prefix [this op field expr]
    (assoc this
      :stmt (conj stmt (format "%s %s (%s)"
                               (nskeyword field)
                               (upper-name op)
                               (->> (if (vector? (first expr))
                                      (first expr)
                                      expr)
                                    parameterize
                                    (join-str ","))))
      :env (into env (sanitize expr)))))

(defn predicate
  ([]         (predicate [] []))
  ([stmt]     (predicate stmt []))
  ([stmt env] (APredicate. stmt env)))

(defn fuse-predicates
  "Combines two predicates into one using AND"
  [p1 p2]
  (if (and (nil? (:env p1)) (nil? (:stmt p1)))
    p2
    (predicate (join-str " AND " [p1 p2])
               (mapcat :env [p1 p2]))))

(defn or*  [& args] (sql-or (predicate) args))
(defn and* [& args] (sql-and (predicate) args))
(defn not* [& args] (sql-not (predicate) args))

(defn =* [& args]
  (if (some #(nil? %) args)
    (spec-op (predicate) (into ["IS"] args))
    (infix (predicate) "=" args)))

(defn !=* [& args]
  (if (some #(nil? %) args)
    (spec-op (predicate) (into ["IS NOT"] args))
    (infix (predicate) "!=" args)))

(defmacro definfixoperator [name op doc]
  `(defn ~name ~doc [& args#]
     (infix (predicate) (name ~op) args#)))

(definfixoperator like :like "LIKE operator:      (like :x \"%y%\"")
(definfixoperator >*   :>    "> operator:         (> :x 5)")
(definfixoperator <*   :<    "< operator:         (< :x 5)")
(definfixoperator <=*  :<=   "<= operator:        (<= :x 5)")
(definfixoperator >=*  :>=   ">= operator:        (>= :x 5)")

(defmacro defprefixoperator [name op doc]
  `(defn ~name ~doc [field# & args#]
     (prefix (predicate) (name ~op) field# args#)))

(defprefixoperator in :in
  "IN operator:  (in :name \"Jack\" \"John\"). Accepts both
   a vector of items or an arbitrary amount of values as seen
   above.")

(defn restrict
  "Returns a query string.

   Takes a raw string with params as %1 %2 %n.

   (restrict 'id=%1 OR id < %2' 15 10) => 'id=15 OR id < 10'"
  [pred & args]
  (apply sql-clause pred args))

(defn restrict-not
  "The inverse of the restrict fn"
  ([ast]         (into [(str "NOT(" ast ")")] (:env ast)))
  ([pred & args] (str "NOT(" (apply sql-clause pred args) ")")))

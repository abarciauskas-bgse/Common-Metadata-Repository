(ns cmr.search.services.acls.granule-acls
  "Contains functions for manipulating granule acls"
  (:require [cmr.search.models.query :as q]
            [cmr.search.services.acl-service :as acl-service]
            [cmr.search.services.acls.acl-helper :as acl-helper]
            [cmr.common.concepts :as c]
            [cmr.search.services.query-walkers.collection-concept-id-extractor :as coll-id-extractor]
            [cmr.search.services.query-walkers.collection-query-resolver :as r]
            [cmr.acl.collection-matchers :as coll-matchers]
            [cmr.search.services.acls.collections-cache :as coll-cache]
            [cmr.common.services.errors :as errors]
            [clojure.set :as set]))


(defmulti filter-applicable-granule-acls
  (fn [coll-ids-by-prov acls]
    (if (empty? coll-ids-by-prov)
      :no-query-coll-ids
      :with-query-coll-ids)))

;; There are no query collection ids so all granule applicable acls are used.
(defmethod filter-applicable-granule-acls :no-query-coll-ids
  [_ acls]
  (filter (comp :granule-applicable :catalog-item-identity) acls))

;; The query contains collection concept ids so they will be used to limit which acls are added to
;; the query.
(defmethod filter-applicable-granule-acls :with-query-coll-ids
  [coll-ids-by-prov acls]

  (filter (fn [acl]
            (let [{{:keys [provider-id] :as cii} :catalog-item-identity} acl
                  acl-collections (get-in cii [:collection-identifier :collections])
                  acl-coll-ids (->> acl-collections
                                    (map :concept-id)
                                    ;; It's possible an ACL refers to an entry title that doesn't exist
                                    (remove nil?))]
              (and (:granule-applicable cii)
                   ;; applies to a provider the user is searching
                   (coll-ids-by-prov provider-id)
                   (or ; The ACL applies to no specific collection
                       (empty? acl-collections)
                       ;; The acl applies to a specific collection that the user is searching
                       (some (coll-ids-by-prov provider-id) acl-coll-ids)))))
          acls))

(defn access-value->query-condition
  "Converts an access value filter from an ACL into a query condition."
  [access-value-filter]
  (when-let [{:keys [include-undefined min-value max-value]} access-value-filter]
    (let [value-cond (when (or min-value max-value)
                       (q/numeric-range-condition :access-value min-value max-value))
          include-undefined-cond (when include-undefined
                                   (q/->NegatedCondition
                                     (q/->ExistCondition :access-value)))]
      (if (and value-cond include-undefined-cond)
        (q/or-conds [value-cond include-undefined-cond])
        (or value-cond include-undefined-cond)))))

(defmulti collection-identifier->query-condition
  "Converts an acl collection identifier to an query condition. Switches implementations based
  on whether the user's query contained collection ids. This implementation assumes the query-coll-ids
  passed in are limited to the provider of the collection identifier."
  (fn [query-coll-ids provider-id collection-identifier]
    (if (empty? query-coll-ids)
      :no-query-coll-ids
      :with-query-coll-ids)))

(defmethod collection-identifier->query-condition :no-query-coll-ids
  [query-coll-ids provider-id collection-identifier]
  (if-let [{:keys [collections access-value]} collection-identifier]
    (let [concept-ids (seq (filter identity ; remove concept ids if nil. acls may reference deleted colls.
                                   (map :concept-id collections)))
          concept-id-cond (when concept-ids
                            (q/string-conditions :collection-concept-id concept-ids true))
          access-value-cond (some-> (access-value->query-condition access-value)
                                    q/->CollectionQueryCondition)]
      (if (and concept-id-cond access-value-cond)
        (q/and-conds [concept-id-cond access-value-cond])
        (or concept-id-cond access-value-cond)))
    ;; No other collection info provided so every collection in provider is possible
    (q/->CollectionQueryCondition
      (q/string-condition :provider-id provider-id true false))))

(defmethod collection-identifier->query-condition :with-query-coll-ids
  [query-coll-ids provider-id collection-identifier]
  (if-let [{:keys [collections access-value]} collection-identifier]
    (let [concept-ids (if collections
                        (set/intersection query-coll-ids (set (filter identity
                                                                      (map :concept-id collections))))
                        query-coll-ids)
          concept-id-cond (when concept-ids
                            (q/string-conditions :collection-concept-id concept-ids true))
          access-value-cond (some-> (access-value->query-condition access-value)
                                    q/->CollectionQueryCondition)]
      (if (and concept-id-cond access-value-cond)
        (q/and-conds [concept-id-cond access-value-cond])
        (or concept-id-cond access-value-cond)))
    ;; No other collection info provided so every collection in provider is possible
    ;; We limit by the items specified in the query.
    (q/string-conditions :collection-concept-id query-coll-ids true)))

(defn granule-identifier->query-cond
  "Converts an acl granule identifier into a query condition."
  [granule-identifier]
  (some-> granule-identifier
          :access-value
          access-value->query-condition))

(defn acl->query-condition
  "Converts an acl into the equivalent query condition. Ths can return nil if the doesn't grant anything.
  This can happen if it's for one collection that doesn't exist."
  [coll-ids-by-prov acl]
  (let [{:keys [provider-id collection-identifier granule-identifier]} (:catalog-item-identity acl)
        query-coll-ids (set (coll-ids-by-prov provider-id))
        collection-cond (collection-identifier->query-condition
                          query-coll-ids provider-id collection-identifier)
        granule-cond (granule-identifier->query-cond granule-identifier)]
    (if (and collection-cond granule-cond)
      (q/and-conds [collection-cond granule-cond])
      (or collection-cond granule-cond))))

(defn acls->query-condition
  "Converts a list of acls into a query condition. coll-ids-by-prov should be a map of provider ids
  to collection concept ids from the user's query."
  [coll-ids-by-prov acls]
  (if (empty? acls)
    (q/->MatchNoneCondition)
    (if-let [conds (seq (filter identity
                            (map (partial acl->query-condition coll-ids-by-prov) acls)))]
      (q/or-conds conds)
      (q/->MatchNoneCondition))))

;; This expects that collection queries have been resolved before this step.
(defmethod acl-service/add-acl-conditions-to-query :granule
  [context query]
  (let [coll-ids-by-prov (->> (coll-id-extractor/extract-collection-concept-ids query)
                              ;; Group the concept ids by provider
                              (group-by #(:provider-id (c/parse-concept-id %)))
                              ;; Create a set of concept ids per provider
                              (map (fn [[prov concept-ids]]
                                     [prov (set concept-ids)]))
                              (into {}))
        acls (filter-applicable-granule-acls
               coll-ids-by-prov
               (acl-helper/get-acls-applicable-to-token context))
        acl-cond (acls->query-condition coll-ids-by-prov acls)]

    ; (update-in query [:condition] #(q/and-conds [acl-cond %]))
    (r/resolve-collection-queries
      context
      (update-in query [:condition] #(q/and-conds [acl-cond %])))))

(comment

  (def context {:system (get-in user/system [:apps :search])
                :token "ABC-1"})
  (coll-cache/get-collection-map context)

  (acl-service/add-acl-conditions-to-query
    context
    @last-query)

  (def coll-ids-by-prov
    (->> (coll-id-extractor/extract-collection-concept-ids @last-query)
         ;; Group the concept ids by provider
         (group-by #(:provider-id (c/parse-concept-id %)))
         ;; Create a set of concept ids per provider
         (map (fn [[prov concept-ids]]
                [prov (set concept-ids)]))
         (into {})))

  (def acls (filter-applicable-granule-acls
              coll-ids-by-prov
              (acl-helper/get-acls-applicable-to-token context)))

  (def acl (last acls))
  (acl->query-condition coll-ids-by-prov acl)

)

(defn granule-identifier-matches-concept?
  "Returns true if the granule identifier is nil or it matches the concept."
  [gran-id concept]
  (if-let [{:keys [min-value max-value include-undefined]} (:access-value gran-id)]
    (let [access-value (acl-helper/extract-access-value concept)]
      (or (and (nil? access-value) include-undefined)
          (and access-value
               (or (and (and min-value max-value)
                        (>= access-value min-value)
                        (<= access-value max-value))
                   (and min-value (nil? max-value) (>= access-value min-value))
                   (and max-value (nil? min-value) (<= access-value max-value))))))
    true))

(defn collection-identifier-matches-concept?
  "Returns true if the collection identifier is nil or it matches the concept."
  [context coll-id concept]
  (if coll-id
    (let [collection-concept-id (get-in concept [:extra-fields :parent-collection-id])
          collection (coll-cache/get-collection context collection-concept-id)]
      (when-not collection
        (errors/internal-error!
          (format "Collection with id %s was in a granule but was not found using collection cache."
                  collection-concept-id)))
      (coll-matchers/coll-matches-collection-identifier? collection coll-id))
    true))

(defn acl-match-concept?
  "Returns true if the acl matches the concept indicating the concept is permitted."
  [context acl concept]
  (let [{provider-id :provider-id
         gran-id :granule-identifier
         coll-id :collection-identifier} (:catalog-item-identity acl)]
    (and (= provider-id (:provider-id concept))
         (granule-identifier-matches-concept? gran-id concept)
         (collection-identifier-matches-concept? context coll-id concept))))

(defmethod acl-service/acls-match-concept? :granule
  [context acls concept]
  (some #(acl-match-concept? context % concept) acls))





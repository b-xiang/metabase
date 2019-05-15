(ns metabase.query-processor.middleware.annotate-test
  (:require [expectations :refer [expect]]
            [flatland.ordered.map :as ordered-map]
            [metabase
             [driver :as driver]
             [util :as u]]
            [metabase.models.field :refer [Field]]
            [metabase.query-processor
             [store :as qp.store]
             [test-util :as qp.test-util]]
            [metabase.query-processor.middleware.annotate :as annotate]
            [metabase.test.data :as data]
            [toucan.util.test :as tt]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             add-native-column-info                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

;; make sure that `add-native-column-info` can still infer types even if the initial value(s) are `nil` (#4256)
(expect
  [{:name "a", :display_name "A", :base_type :type/Integer, :source :native}
   {:name "b", :display_name "B", :base_type :type/Integer, :source :native}]
  (:cols (#'annotate/add-native-column-info {:columns [:a :b], :rows [[1 nil]
                                                                      [2 nil]
                                                                      [3 nil]
                                                                      [4   5]
                                                                      [6   7]]})))

;; make sure that `add-native-column-info` defaults `base_type` to `type/*` if there are no non-nil
;; values when we peek.
(expect
  [{:name "a", :display_name "A", :base_type :type/*, :source :native}]
  (:cols (#'annotate/add-native-column-info {:columns [:a], :rows [[nil]]})))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              add-mbql-column-info                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

;; make sure columns are comming back the way we'd expect
(expect
  [(-> (Field (data/id :venues :price))
       (dissoc :database_type)
       (assoc :source :fields))]
  (qp.store/with-store
    (qp.store/store-field! (Field (data/id :venues :price)))
    (-> (#'annotate/add-mbql-column-info
         {:query {:fields [[:field-id (data/id :venues :price)]]}}
         {:columns [:price]})
        :cols
        vec)))

;; when an `fk->` form is used, we should add in `:fk_field_id` info about the source Field
;; TODO - we can remove this test
(expect
  [(-> (Field (data/id :categories :name))
       (dissoc :database_type)
       (assoc :fk_field_id (data/id :venues :category_id), :source :fields))]
  (qp.store/with-store
    (qp.store/store-field! (Field (data/id :categories :name)))
    (-> (#'annotate/add-mbql-column-info
         {:query {:fields [[:fk->
                            [:field-id (data/id :venues :category_id)]
                            [:field-id (data/id :categories :name)]]]}}
         {:columns [:name]})
        :cols
        vec)))

;; we should get `:fk_field_id` and information where possible when using `:joined-field` clauses
;; TODO
(expect
  [(-> (Field (data/id :categories :name))
       (dissoc :database_type)
       (assoc :fk_field_id (data/id :venues :category_id), :source :fields))]
  (qp.store/with-store
    (qp.store/store-field! (Field (data/id :categories :name)))
    (-> (#'annotate/add-mbql-column-info
         {:query {:fields [[:joined-field "CATEGORIES__via__CATEGORY_ID" (data/id :categories :name)]]}}
         {:columns [:name]})
        :cols
        vec)))

;; when a `:datetime-field` form is used, we should add in info about the `:unit`
(expect
  [(-> (Field (data/id :venues :price))
       (dissoc :database_type)
       (assoc :unit :month, :source :fields))]
  (qp.store/with-store
    (qp.store/store-field! (Field (data/id :venues :price)))
    (-> (#'annotate/add-mbql-column-info
         {:query {:fields [[:datetime-field [:field-id (data/id :venues :price)] :month]]}}
         {:columns [:price]})
        :cols
        vec)))

;; datetime unit should work on field literals too
(expect
  [{:name         "price"
    :base_type    :type/Number
    :display_name "Price"
    :unit         :month
    :source       :fields}]
  (-> (#'annotate/add-mbql-column-info
       {:query {:fields [[:datetime-field [:field-literal "price" :type/Number] :month]]}}
       {:columns [:price]})
      :cols
      vec))

;; when binning-strategy is used, include `:binning_info`
(expect
  [{:name         "price"
    :base_type    :type/Number
    :display_name "Price"
    :unit         :month
    :source       :fields
    :binning_info {:num_bins         10
                   :bin_width        5
                   :min_value        -100
                   :max_value        100
                   :binning_strategy :num-bins}}]
  (-> (#'annotate/add-mbql-column-info
       {:query {:fields [[:binning-strategy
                          [:datetime-field [:field-literal "price" :type/Number] :month]
                          :num-bins
                          10
                          {:num-bins  10
                           :bin-width 5
                           :min-value -100
                           :max-value 100}]]}}
       {:columns [:price]})
      :cols
      vec))

;; test that added information about aggregations looks the way we'd expect
(defn- aggregation-name [ag-clause]
  (binding [driver/*driver* :h2]
    (annotate/aggregation-name ag-clause)))

(expect
  "count"
  (aggregation-name [:count]))

(expect
  "count"
  (aggregation-name [:distinct [:field-id 1]]))

(expect
  "sum"
  (aggregation-name [:sum [:field-id 1]]))

(expect
  "count + 1"
  (aggregation-name [:+ [:count] 1]))

(expect
  "min + (2 * avg)"
  (aggregation-name [:+ [:min [:field-id 1]] [:* 2 [:avg [:field-id 2]]]]))

(expect
  "min + (2 * avg * 3 * (max - 4))"
  (aggregation-name [:+
                     [:min [:field-id 1]]
                     [:*
                      2
                      [:avg [:field-id 2]]
                      3
                      [:-
                       [:max [:field-id 3]]
                       4]]]))

(expect
  "x"
  (aggregation-name [:named [:+ [:min [:field-id 1]] [:* 2 [:avg [:field-id 2]]]] "x"]))

(expect
  "My Cool Aggregation"
  (aggregation-name [:named [:avg [:field-id 2]] "My Cool Aggregation"]))

;; make sure custom aggregation names get included in the col info
(defn- col-info-for-aggregation-clause [clause]
  (binding [driver/*driver* :h2]
    (#'annotate/col-info-for-aggregation-clause clause)))

(expect
  {:base_type    :type/Float
   :special_type :type/Number
   :name         "count / 2"
   :display_name "count / 2"}
  (col-info-for-aggregation-clause [:/ [:count] 2]))

(expect
  {:base_type    :type/Float
   :special_type :type/Number
   :name         "sum"
   :display_name "sum"}
  (qp.store/with-store
    (data/$ids venues
      (qp.store/store-field! (Field $price))
      (col-info-for-aggregation-clause [:sum [:+ [:field-id $price] 1]]))))

;; if a driver is kind enough to supply us with some information about the `:cols` that come back, we should include
;; that information in the results. Their information should be preferred over ours
(expect
  {:cols    [{:name         "totalEvents"
              :display_name "Total Events"
              :base_type    :type/Text
              :source       :aggregation}]
   :columns ["totalEvents"]}
  (binding [driver/*driver* :h2]
    ((annotate/add-column-info (constantly {:cols    [{:name         "totalEvents"
                                                       :display_name "Total Events"
                                                       :base_type    :type/Text}]
                                            :columns ["totalEvents"]}))
     {:database (data/id)
      :type     :query
      :query    {:source-table (data/id :venues)
                 :aggregation  [[:metric "ga:totalEvents"]]}})))

;; Make sure columns always come back with a unique `:name` key (#8759)
(expect
  {:cols
   [{:base_type    :type/Number
     :special_type :type/Number
     :name         "count"
     :display_name "count"
     :source       :aggregation}
    {:source       :aggregation
     :name         "sum"
     :display_name "sum"
     :base_type    :type/Number}
    {:base_type    :type/Number
     :special_type :type/Number
     :name         "count_2"
     :display_name "count"
     :source       :aggregation}
    {:base_type    :type/Number
     :special_type :type/Number
     :name         "count_2_2"
     :display_name "count_2"
     :source       :aggregation}]
   :columns ["count" "sum" "count" "count_2"]}
  (binding [driver/*driver* :h2]
    ((annotate/add-column-info (constantly {:cols    [{:name         "count"
                                                       :display_name "count"
                                                       :base_type    :type/Number}
                                                      {:name         "sum"
                                                       :display_name "sum"
                                                       :base_type    :type/Number}
                                                      {:name         "count"
                                                       :display_name "count"
                                                       :base_type    :type/Number}
                                                      {:name         "count_2"
                                                       :display_name "count_2"
                                                       :base_type    :type/Number}]
                                            :columns ["count" "sum" "count" "count_2"]}))
     {:database (data/id)
      :type     :query
      :query    {:source-table (data/id :venues)
                 :aggregation  [[:count] [:sum] [:count] [:named [:count] "count_2"]]}})))

;; make sure expressions come back with the right set of keys, including `:expression_name` (#8854)
(expect
  {:name            "discount_price"
   :display_name    "discount_price"
   :base_type       :type/Float
   :special_type    :type/Number
   :expression_name "discount_price"
   :source          :fields}
  (-> (qp.test-util/with-everything-store
        ((annotate/add-column-info (constantly {}))
         {:database (data/id)
          :type     :query
          :query    (data/$ids [venues {:wrap-field-ids? true}]
                      {:source-table $$table
                       :expressions  {"discount_price" [:* 0.9 [:field-id $price]]}
                       :fields       [$name [:expression "discount_price"]]
                       :limit        10})}))
      :cols
      second))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           result-rows-maps->vectors                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

;; If a driver returns result rows as a sequence of maps, does the `result-rows-maps->vectors` convert them to a
;; sequence of vectors in the correct order?
(expect
  {:rows    [[1 "Red Medicine" 4 10.0646 -165.374 3]]
   :columns ["ID" "NAME" "CATEGORY_ID" "LATITUDE" "LONGITUDE" "PRICE"]}
  (qp.test-util/with-everything-store
    (driver/with-driver :h2
      (let [results {:rows [{:CATEGORY_ID 4
                             :ID          1
                             :LATITUDE    10.0646
                             :LONGITUDE   -165.374
                             :NAME        "Red Medicine"
                             :PRICE       3}]}]
        ((annotate/result-rows-maps->vectors (constantly results))
         {:database (data/id)
          :type     :query
          :query    (data/$ids [venues {:wrap-field-ids? true}]
                               {:source-table $$table
                                :fields       [$id $name $category_id $latitude $longitude $price]
                                :limit        1})})))))

;; if a driver would have returned result rows as a sequence of maps, but query returned no results, middleware should
;; still add `:columns` info
(expect
  {:rows    []
   :columns ["ID" "NAME" "CATEGORY_ID" "LATITUDE" "LONGITUDE" "PRICE"]}
  (qp.test-util/with-everything-store
    (driver/with-driver :h2
      (let [results {:rows []}]
        ((annotate/result-rows-maps->vectors (constantly results))
         {:database (data/id)
          :type     :query
          :query    (data/$ids [venues {:wrap-field-ids? true}]
                      {:source-table $$table
                       :fields       [$id $name $category_id $latitude $longitude $price]
                       :limit        1})})))))

;; `result-rows-maps->vectors` should preserve sort order of columns in the first result row for native queries
;; (hopefully the driver is using Flatland `ordered-map` as suggested)
(expect
  {:rows    [[1 10.0646 -165.374 "Red Medicine" 3]]
   :columns ["ID" "LATITUDE" "LONGITUDE" "NAME" "PRICE"]}
  (qp.test-util/with-everything-store
    (driver/with-driver :h2
      (let [results {:rows [(ordered-map/ordered-map
                             :ID          1
                             :LATITUDE    10.0646
                             :LONGITUDE   -165.374
                             :NAME        "Red Medicine"
                             :PRICE       3)]}]
        ((annotate/result-rows-maps->vectors (constantly results))
         {:database (data/id)
          :type     :native})))))

;; Does `result-rows-maps->vectors` handle multiple aggregations of the same type? Should assume column keys are
;; deduplicated using the MBQL lib logic
(expect
  {:rows    [[2 409 20]
             [3  56  4]]
   :columns ["CATEGORY_ID" "sum" "sum_2"]}
  (qp.test-util/with-everything-store
    (driver/with-driver :h2
      (let [results {:rows [{:CATEGORY_ID 2
                             :sum         409
                             :sum_2       20}
                            {:CATEGORY_ID 3
                             :sum         56
                             :sum_2       4}]}]
        ((annotate/result-rows-maps->vectors (constantly results))
         {:database (data/id)
          :type     :query
          :query    (data/$ids [venues {:wrap-field-ids? true}]
                      {:source-table $$table
                       :aggregation  [[:sum $id]
                                      [:sum $price]]
                       :breakout     [$category_id]
                       :limit        2})})))))

;; For fields with parents we should return them with a combined name including parent's name
(tt/expect-with-temp [Field [parent {:name "parent", :table_id (data/id :venues)}]
                      Field [child  {:name "child",  :table_id (data/id :venues), :parent_id (u/get-id parent)}]]
  {:description     nil
   :table_id        (data/id :venues)
   :special_type    nil
   :name            "parent.child"
   :settings        nil
   :parent_id       (u/get-id parent)
   :id              (u/get-id child)
   :visibility_type :normal
   :display_name    "Child"
   :fingerprint     nil
   :base_type       :type/Text}
  (qp.test-util/with-everything-store
    (#'annotate/col-info-for-field-clause [:field-id (u/get-id child)])))

;; nested-nested fields should include grandparent name (etc)
(tt/expect-with-temp [Field [grandparent {:name "grandparent", :table_id (data/id :venues)}]
                      Field [parent      {:name "parent",      :table_id (data/id :venues), :parent_id (u/get-id grandparent)}]
                      Field [child       {:name "child",       :table_id (data/id :venues), :parent_id (u/get-id parent)}]]
  {:description     nil
   :table_id        (data/id :venues)
   :special_type    nil
   :name            "grandparent.parent.child"
   :settings        nil
   :parent_id       (u/get-id parent)
   :id              (u/get-id child)
   :visibility_type :normal
   :display_name    "Child"
   :fingerprint     nil
   :base_type       :type/Text}
  (qp.test-util/with-everything-store
    (#'annotate/col-info-for-field-clause [:field-id (u/get-id child)])))

(ns cmr.umm.test.iso-mends.collection
  "Tests parsing and generating ISO Collection XML."
  (:require [clojure.test :refer :all]
            [cmr.common.test.test-check-ext :refer [defspec]]
            [clojure.test.check.properties :refer [for-all]]
            [clojure.test.check.generators :as gen]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [cmr.common.joda-time]
            [cmr.common.date-time-parser :as p]
            [cmr.umm.test.generators.collection :as coll-gen]
            [cmr.umm.iso-mends.collection :as c]
            [cmr.umm.echo10.collection :as echo10-c]
            [cmr.umm.echo10.core :as echo10]
            [cmr.umm.collection :as umm-c]
            [cmr.umm.iso-mends.core :as iso]
            [cmr.umm.test.echo10.collection :as test-echo10])
  (:import cmr.spatial.mbr.Mbr))

(defn- related-urls->expected-parsed
  "Returns the expected parsed related-urls for the given related-urls."
  [related-urls]
  (seq (map #(assoc % :size nil) related-urls)))

(defn- sensors->expected-parsed
  "Return the expected parsed sensors for the given sensors."
  [sensors]
  (seq (map #(assoc % :technique nil) sensors)))

(defn- instrument->expected-parsed
  "Return the expected parsed instrument for the given instrument."
  [instrument]
  (-> instrument
      (assoc :technique nil)
      (update-in [:sensors] sensors->expected-parsed)))

(defn- instruments->expected-parsed
  "Return the expected parsed instruments for the given instruments."
  [instruments]
  (seq (map instrument->expected-parsed instruments)))

(defn- platform->expected-parsed
  "Return the expected parsed platform for the given platform."
  [platform]
  (let [instruments (:instruments platform)]
    (-> platform
        (assoc :characteristics nil)
        (assoc :instruments (instruments->expected-parsed instruments)))))

(defn- platforms->expected-parsed
  "Returns the expected parsed platforms for the given platforms."
  [platforms]
  (seq (map platform->expected-parsed platforms)))

(defn- umm->expected-parsed-iso
  "Modifies the UMM record for testing ISO. ISO contains a subset of the total UMM fields so certain
  fields are removed for comparison of the parsed record"
  [coll]
  (let [{{:keys [short-name long-name version-id processing-level-id]} :product
         :keys [entry-title spatial-coverage]} coll
        entry-id (str short-name "_" version-id)
        range-date-times (get-in coll [:temporal :range-date-times])
        single-date-times (get-in coll [:temporal :single-date-times])
        temporal (if (seq range-date-times)
                   (umm-c/map->Temporal {:range-date-times range-date-times
                                         :single-date-times []
                                         :periodic-date-times []})
                   (when (seq single-date-times)
                     (umm-c/map->Temporal {:range-date-times []
                                           :single-date-times single-date-times
                                           :periodic-date-times []})))
        organizations (seq (filter #(not (= :distribution-center (:type %))) (:organizations coll)))]
    (-> coll
        ;; ISO does not have entry-id and we generate it as concatenation of short-name and version-id
        (assoc :entry-id entry-id)
        ;; ISO does not have collection-data-type
        (assoc-in [:product :collection-data-type] nil)
        ;; There is no delete-time in ISO
        (assoc-in [:data-provider-timestamps :delete-time] nil)
        ;; ISO does not have periodic-date-times
        (assoc :temporal temporal)
        ;; ISO does not have distribution centers as Organization
        (assoc :organizations organizations)
        ;; ISO does not support sensor technique or platform characteristics
        (update-in [:platforms] platforms->expected-parsed)
        ;; ISO spatial mapping is incomplete right now
        (dissoc :spatial-coverage)
        ;; ISO AdditionalAttributes mapping is incomplete right now
        (dissoc :product-specific-attributes)
        ;; ISO does not support size in RelatedURLs
        (update-in [:related-urls] related-urls->expected-parsed)
        ;; ISO does not fully support two-d-coordinate-systems
        (dissoc :two-d-coordinate-systems)
        ;; We don't use these two fields during xml generation as they are not needed for ISO
        ;; so we set them to the defaults here.
        (assoc :contact-email "support@earthdata.nasa.gov")
        (assoc :contact-name "undefined")
        umm-c/map->UmmCollection)))

(defspec generate-collection-is-valid-xml-test 100
  (for-all [collection coll-gen/collections]
    (let [xml (iso/umm->iso-mends-xml collection)]
      (and
        (> (count xml) 0)
        (= 0 (count (c/validate-xml xml)))))))

(defspec generate-and-parse-collection-test 100
  (for-all [collection coll-gen/collections]
    (let [xml (iso/umm->iso-mends-xml collection)
          parsed (c/parse-collection xml)
          expected-parsed (umm->expected-parsed-iso collection)]
      (= parsed expected-parsed))))

(defspec generate-and-parse-collection-between-formats-test 100
  (for-all [collection coll-gen/collections]
    (let [xml (iso/umm->iso-mends-xml collection)
          parsed-iso (c/parse-collection xml)
          echo10-xml (echo10/umm->echo10-xml parsed-iso)
          parsed-echo10 (echo10-c/parse-collection echo10-xml)
          expected-parsed (test-echo10/umm->expected-parsed-echo10 (umm->expected-parsed-iso collection))]
      (and (= parsed-echo10 expected-parsed)
           (= 0 (count (echo10-c/validate-xml echo10-xml)))))))

;; This is a made-up include all fields collection xml sample for the parse collection test
(def all-fields-collection-xml
  (slurp (io/file (io/resource "data/iso_mends/all_fields_iso_collection.xml"))))

(def valid-collection-xml
  (slurp (io/file (io/resource "data/iso_mends/sample_iso_collection.xml"))))

(deftest parse-collection-test
  (let [expected (umm-c/map->UmmCollection
                   {:entry-id "MINIMAL_1"
                    :entry-title "A minimal valid collection V 1"
                    :summary "A minimal valid collection"
                    :product (umm-c/map->Product
                               {:short-name "MINIMAL"
                                :long-name "A minimal valid collection"
                                :version-id "1"
                                :processing-level-id "1B"})
                    :access-value 4.2
                    :data-provider-timestamps (umm-c/map->DataProviderTimestamps
                                                {:insert-time (p/parse-datetime "1999-12-30T19:00:00-05:00")
                                                 :update-time (p/parse-datetime "1999-12-31T19:00:00-05:00")})
                    :spatial-keywords ["Word-2" "Word-1" "Word-0"]
                    :temporal-keywords ["Word-5" "Word-3" "Word-4"]
                    :temporal
                    (umm-c/map->Temporal
                      {:range-date-times
                       [(umm-c/map->RangeDateTime
                          {:beginning-date-time (p/parse-datetime "1996-02-24T22:20:41-05:00")
                           :ending-date-time (p/parse-datetime "1997-03-24T22:20:41-05:00")})
                        (umm-c/map->RangeDateTime
                          {:beginning-date-time (p/parse-datetime "1998-02-24T22:20:41-05:00")
                           :ending-date-time (p/parse-datetime "1999-03-24T22:20:41-05:00")})]
                       :single-date-times
                       [(p/parse-datetime "2010-01-05T05:30:30.550-05:00")]
                       :periodic-date-times []})
                    :science-keywords
                    [(umm-c/map->ScienceKeyword
                       {:category "EARTH SCIENCE"
                        :topic "CRYOSPHERE"
                        :term "SNOW/ICE"
                        :variable-level-1 "ALBEDO"
                        :variable-level-2 "BETA"
                        :variable-level-3 "GAMMA"
                        :detailed-variable "DETAILED"})
                     (umm-c/map->ScienceKeyword
                       {:category "EARTH SCIENCE"
                        :topic "CRYOSPHERE"
                        :term "SEA ICE"
                        :variable-level-1 "REFLECTANCE"})]
                    ; :product-specific-attributes
                    ; [(umm-c/map->ProductSpecificAttribute
                    ;    {:name "String add attrib"
                    ;     :description "something string"
                    ;     :data-type :string
                    ;     :parameter-range-begin "alpha"
                    ;     :parameter-range-end "bravo"
                    ;     :value "alpha1"})
                    ;  (umm-c/map->ProductSpecificAttribute
                    ;    {:name "Float add attrib"
                    ;     :description "something float"
                    ;     :data-type :float
                    ;     :parameter-range-begin 0.1
                    ;     :parameter-range-end 100.43
                    ;     :value 12.3})]
                    :platforms
                    [(umm-c/map->Platform
                       {:short-name "RADARSAT-1"
                        :long-name "RADARSAT-LONG-1"
                        :type "Spacecraft"
                        :instruments [(umm-c/->Instrument
                                        "SAR"
                                        "SAR long name"
                                        nil
                                        [(umm-c/->Sensor "SNA" "SNA long name" nil)
                                         (umm-c/->Sensor "SNB" nil nil)])
                                      (umm-c/->Instrument "MAR" nil nil nil)]})
                     (umm-c/map->Platform
                       {:short-name "RADARSAT-2"
                        :long-name "RADARSAT-LONG-2"
                        :type "Spacecraft-2"
                        :instruments nil})]
                    :projects
                    [(umm-c/map->Project
                       {:short-name "ESI"
                        :long-name "Environmental Sustainability Index"})
                     (umm-c/map->Project
                       {:short-name "EVI"
                        :long-name "Environmental Vulnerability Index"})
                     (umm-c/map->Project
                       {:short-name "EPI"
                        :long-name "Environmental Performance Index"})]
                    ; :two-d-coordinate-systems
                    ; [(umm-c/map->TwoDCoordinateSystem {:name "name0"})
                    ;  (umm-c/map->TwoDCoordinateSystem {:name "name1"})]
                    :related-urls
                    [(umm-c/map->RelatedURL
                       {:type "GET DATA"
                        :url "http://ghrc.nsstc.nasa.gov/hydro/details.pl?ds=dc8capac"})
                     (umm-c/map->RelatedURL
                       {:type "GET DATA"
                        :url "http://camex.nsstc.nasa.gov/camex3/"})
                     (umm-c/map->RelatedURL
                       {:type "VIEW RELATED INFORMATION"
                        :url "http://ghrc.nsstc.nasa.gov/uso/ds_docs/camex3/dc8capac/dc8capac_dataset.html"})
                     (umm-c/map->RelatedURL
                       {:type "GET RELATED VISUALIZATION"
                        :url "ftp://camex.nsstc.nasa.gov/camex3/dc8capac/browse/"
                        :description "Some description."
                        :title "Some description."})]
                    :associated-difs ["DIF-255" "DIF-256" "DIF-257"]
                    :organizations
                    [(umm-c/map->Organization
                       {:type :processing-center
                        :org-name "SEDAC PC"})
                     (umm-c/map->Organization
                       {:type :archive-center
                        :org-name "SEDAC AC"})]
                    :contact-email "jsmith@nasa.gov"
                    :contact-name "John Smith"
                    })
        actual (c/parse-collection all-fields-collection-xml)]
    (is (= expected actual))))

(deftest validate-xml
  (testing "valid xml"
    (is (= 0 (count (c/validate-xml valid-collection-xml)))))
  (testing "invalid xml"
    (is (= [(str "Line 15 - cvc-complex-type.2.4.a: Invalid content was found "
                 "starting with element 'gmd:XXXX'. One of "
                 "'{\"http://www.isotc211.org/2005/gmd\":fileIdentifier, "
                 "\"http://www.isotc211.org/2005/gmd\":language, "
                 "\"http://www.isotc211.org/2005/gmd\":characterSet, "
                 "\"http://www.isotc211.org/2005/gmd\":parentIdentifier, "
                 "\"http://www.isotc211.org/2005/gmd\":hierarchyLevel, "
                 "\"http://www.isotc211.org/2005/gmd\":hierarchyLevelName, "
                 "\"http://www.isotc211.org/2005/gmd\":contact}' is expected.")]
           (c/validate-xml (s/replace valid-collection-xml "fileIdentifier" "XXXX"))))))

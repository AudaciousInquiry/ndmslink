package com.lantanagroup.link.ndms;

import com.lantanagroup.link.*;
import com.lantanagroup.link.Constants;
import com.lantanagroup.link.auth.LinkCredentials;
import com.lantanagroup.link.config.api.ApiConfig;
import com.lantanagroup.link.model.ReportContext;
import com.lantanagroup.link.model.ReportCriteria;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

@Component
public class NdmsMeasureReportGenerator implements IMeasureReportGenerator {
    private static final Logger logger = LoggerFactory.getLogger(NdmsMeasureReportGenerator.class);

    private CodeSystem trac2esCodeSystem = null;
    private final NdmsUtility ndmsUtility = new NdmsUtility();

    @Override
    public void generate(StopwatchManager stopwatchManager,
                         ReportContext reportContext,
                         ReportContext.MeasureContext measureContext,
                         ReportCriteria criteria,
                         ApiConfig config,
                         LinkCredentials user,
                         IReportAggregator reportAggregator)  throws ParseException, ExecutionException, InterruptedException, IOException {
        logger.info("Patient list is : {}", measureContext.getPatientsOfInterest().size());
        ForkJoinPool forkJoinPool = config.getMeasureEvaluationThreads() != null
                ? new ForkJoinPool(config.getMeasureEvaluationThreads())
                : ForkJoinPool.commonPool();

        try {

            final Date startDate = Helper.parseFhirDate(criteria.getPeriodStart());
            final Date endDate = Helper.parseFhirDate(criteria.getPeriodEnd());

            // Read in NHSN bed list
            // TODO: Will revisit this is a bit of a mess to get initial Measures for comparison
            String nhsnCsvData = Files.readString(Path.of(config.getNhsnBedListCsvFile()));
            final List<NhsnLocation> nhsnLocations = NhsnLocation.parseCsvData(nhsnCsvData);

            List<MeasureReport> patientMeasureReports = forkJoinPool.submit(() ->
                    measureContext.getPatientsOfInterest().parallelStream().filter(patient -> !StringUtils.isEmpty(patient.getId())).map(patient -> {

                        logger.info("Generating measure report for patient {}", patient);
                        MeasureReport patientMeasureReport = new MeasureReport();
                        String patientDataBundleId = ReportIdHelper.getPatientDataBundleId(reportContext.getMasterIdentifier(), patient.getId());
                        try {

                            logger.info("Patient Bundle ID: {}", patientDataBundleId);

                            // Get the Patient bundle from the FHIR Data Store Server
                            FhirDataProvider fhirStoreProvider = new FhirDataProvider(config.getDataStore());
                            Bundle patientBundle = fhirStoreProvider.getBundleById(patientDataBundleId);

                            // Get active Encounter(s) from Bundle by report generation start/end date
                            List<Encounter> encounters = patientBundle.getEntry().stream()
                                    .map(Bundle.BundleEntryComponent::getResource)
                                    .filter(Encounter.class::isInstance)
                                    .map(Encounter.class::cast)
                                    .filter(encounter -> isEncounterInRange(encounter, startDate, endDate, patientDataBundleId))
                                    .collect(Collectors.toList());

                            // Loop encounters and pull associated Location options
                            List<Location> relevantLocations = new ArrayList<>();
                            encounters.forEach(encounter -> {
                                List<Location> locs = getLocationsForEncounter(encounter, patientBundle);
                                relevantLocations.addAll(locs);
                            });

                            // Pull Location identifiers from Encounters if the Location has a period.start / period.end
                            // that falls in range of the passed in start/end dates when generating the reports.
                            // TODO add relevat location check onto EncounterLocationComponent after Encounter
                            // has been identified to contain a valid Location via date
                            // I think we are pulling locations in that DO NOT have a date.  Becauce we are pulling the
                            // encounter that has at least 1 relevant Locaiton with a date.  And then pulling all those locations.
                            List<String> relevantLocationIdentifiers = patientBundle.getEntry().stream()
                                    .map(Bundle.BundleEntryComponent::getResource)
                                    .filter(Encounter.class::isInstance)
                                    .map(Encounter.class::cast)
                                    .filter(encounter -> hasRelevantLocation(encounter, startDate, endDate, patientDataBundleId))
                                    .flatMap(encounter -> encounter.getLocation().stream())
                                    .map(location -> location.getLocation().getReference())
                                    .filter(Objects::nonNull)  // remove nulls
                                    .filter(ref -> !ref.isEmpty())  // remove empty strings
                                    .distinct()
                                    .collect(Collectors.toList());

                            List<String> relevantLocationIdentifiersNOTGOOD = patientBundle.getEntry().stream()
                                    .map(Bundle.BundleEntryComponent::getResource)
                                    .filter(Encounter.class::isInstance)
                                    .map(Encounter.class::cast)
                                    .flatMap(encounter -> encounter.getLocation().stream())
                                    // Now we filter the locations themselves using isLocationRelevantOrig
                                    .filter(location -> isLocationRelevantOrig(location, startDate, endDate, patientDataBundleId))
                                    .map(location -> location.getLocation().getReference())
                                    .filter(Objects::nonNull)  // remove nulls
                                    .filter(ref -> !ref.isEmpty())  // remove empty strings
                                    .distinct()
                                    .collect(Collectors.toList());

                            // Pull relevant Location resources using the list if ID's generated in previous step.
                            // Filter out any Location that has no aliases as those are used for finding the Nebraska
                            // Med bed code.
                            List<Location> relevantLocationsOLD = patientBundle.getEntry().stream()
                                    .map(Bundle.BundleEntryComponent::getResource)
                                    .filter(Location.class::isInstance)
                                    .map(Location.class::cast)
                                    .filter(location -> relevantLocationIdentifiers.contains("Location/" + location.getIdElement().getIdPart()))
                                    .filter(location -> location.hasAlias() && !location.getAlias().isEmpty())
                                    .collect(Collectors.toList());

                            for (Location location : relevantLocations) {

                                List<String> aliases = location.getAlias().stream()
                                        .map(StringType::getValue)
                                        .collect(Collectors.toList());

                                Location partOf = (Location)location.getPartOf().getResource();
                                if (partOf != null) {
                                    List<String> partOfAliases = partOf.getAlias().stream().map(StringType::getValue).collect(Collectors.toList());
                                    aliases.addAll(partOfAliases);
                                }

                                if (!aliases.isEmpty()) {

                                    Optional<NhsnLocation> nhsnLocation = getNhsnLocation(nhsnLocations, aliases);
                                    if (!nhsnLocation.isPresent()) {

                                        // We want to log the location to go back and research
                                        logger.info("UNMAPPED|{}|{}|{}|{}|{}|{}|{}",
                                                patientDataBundleId,
                                                location.getId(),
                                                location.getName(),
                                                location.getAlias().stream().map(StringType::toString).collect(Collectors.joining(",")),
                                                location.getPartOf().getDisplay(),
                                                Optional.ofNullable(partOf).map(p -> p.getId()).orElse(""),
                                                Optional.ofNullable(partOf).map(p -> p.getAlias().stream().map(StringType::toString).collect(Collectors.joining(","))).orElse("")
                                        );
                                    } else {
                                        NhsnLocation foundLocation = nhsnLocation.get();
                                        logger.info("ISMAPPED|{}|{}|{}|{}|{}|{}|{}|{}",
                                                foundLocation.getOrganizationId(),
                                                foundLocation.getCode(),
                                                foundLocation.getUnit(),
                                                foundLocation.getCdcCode(),
                                                foundLocation.getLocationCode(),
                                                foundLocation.getCdcLabel(),
                                                foundLocation.getStatus(),
                                                foundLocation.getTrac2es()
                                        );
                                    }
                                    nhsnLocation.ifPresent(
                                    loc -> {

                                                // Lookup TRAC2ES Code
                                                // TODO: !!! Right now if we do not have a NebMed to TRAC2ES Map then we do not include
                                                //       Looking for feedback from NDMS re: this.  So this may change.
                                                CodeableConcept groupCodeableConcept = getTrac2esCodeableConcept(config.getEvaluationService(), config.getTrac2esCodeSystem(), loc.getTrac2es());

                                                if (groupCodeableConcept != null) {
                                                    MeasureReport.MeasureReportGroupComponent group = new MeasureReport.MeasureReportGroupComponent();
                                                    group.setCode(groupCodeableConcept);

                                                    MeasureReport.MeasureReportGroupPopulationComponent occupied = new MeasureReport.MeasureReportGroupPopulationComponent();
                                                    CodeableConcept populationOccupiedCodeableConcept = ndmsUtility.getOccPopulationCodeByTrac2es(config.getEvaluationService(), config.getTrac2esNdmsConceptMap(), loc.getTrac2es());
                                                    occupied.setCode(populationOccupiedCodeableConcept);
                                                    occupied.setCount(1);

                                                    group.addPopulation(occupied);

                                                    patientMeasureReport.addGroup(group);

                                                    //logger.info("Patient Bundle Mapped: {}", patientDataBundleId);

                                                }
                                            }
                                    );
                                }
                            }

                        } catch (Exception ex) {
                            logger.error("Issue generating patient measure report for {}, error {}", patientDataBundleId, ex.getMessage());
                        }

                        String measureReportId = ReportIdHelper.getPatientMeasureReportId(measureContext.getReportId(), patient.getId());
                        patientMeasureReport.setId(measureReportId);
                        // Tag individual MeasureReport as patient-data as it references a patient and will be found for expunge
                        patientMeasureReport.getMeta().addTag(Constants.MAIN_SYSTEM, Constants.PATIENT_DATA_TAG,"");

                        logger.info("Persisting patient {} measure report with id {}", patient, measureReportId);
                        Stopwatch stopwatch = stopwatchManager.start("store-measure-report");
                        reportContext.getFhirProvider().updateResource(patientMeasureReport);
                        stopwatch.stop();

                        // Add Location Info to MeasureReport
                        ndmsUtility.addLocationSubjectToMeasureReport(patientMeasureReport, reportContext.getReportLocation());

                        return patientMeasureReport;
                    }).collect(Collectors.toList())).get();
            // to avoid thread collision remove saving the patientMeasureReport on the FhirServer from the above parallelStream
            // pass them to aggregators using measureContext
            measureContext.setPatientReports(patientMeasureReports);
        } finally {
            if (forkJoinPool != null) {
                forkJoinPool.shutdown();
            }
        }
        MeasureReport masterMeasureReport = reportAggregator.generate(criteria, reportContext, measureContext, config);

        // Add  Organization Information to MeasureReport
        ndmsUtility.addLocationSubjectToMeasureReport(masterMeasureReport, reportContext.getReportLocation());

        measureContext.setMeasureReport(masterMeasureReport);

    }

    @Override
    public void store(ReportContext.MeasureContext measureContext, ReportContext reportContext) {
        measureContext.getPatientReports().forEach(report -> reportContext.getFhirProvider().updateResource(report));


        // Tag & Store aggregated report
        MeasureReport aggregatedReport = measureContext.getMeasureReport();
        aggregatedReport.getMeta().addTag(Constants.NDMS_AGGREGATE_MEASURE_REPORT);
        reportContext.getFhirProvider().updateResource(aggregatedReport);

        // Tag as the "current" report
        aggregatedReport.getMeta().addTag(Constants.NDMS_CURRENT_AGGREGATE_MEASURE_REPORT);
        // Update ID to be for the Location
        aggregatedReport.setId(reportContext.getReportLocation().getIdElement().getIdPart());
        reportContext.getFhirProvider().updateResource(aggregatedReport);

    }

    private static boolean hasRelevantLocation(Encounter encounter, Date startDate, Date endDate, String patientBundleId) {
        boolean returnValue = encounter.getLocation().stream()
                .anyMatch(location -> isLocationRelevantOrig(location, startDate, endDate, patientBundleId));

        return returnValue;
    }

    private static boolean isEncounterInRange(Encounter encounter, Date startDate, Date endDate, String patientBundleId) {
        if (encounter.getPeriod() == null) {
            return false;
        }

        Date encounterStart = encounter.getPeriod().getStart();
        Date encounterEnd = encounter.getPeriod().getEnd();

        if (encounterStart == null && encounterEnd == null) {
            return false;
        }

        boolean returnValue =  (encounterStart == null || !encounterStart.after(startDate)) &&
                (encounterEnd == null || !encounterEnd.before(endDate));

        return returnValue;


    }

    private static boolean isLocationRelevantOrig(Encounter.EncounterLocationComponent location, Date startDate, Date endDate, String patientBundleId) {

        if (location.getPeriod() == null) {
            return false; // If no period is specified, consider it NOT relevant
        }

        Date locationStart = location.getPeriod().getStart();
        Date locationEnd = location.getPeriod().getEnd();

        // Period exists, but both start/stop don't exist consider NOT relevant
        if (locationStart == null && locationEnd == null) {
            return false;
        }

        boolean returnValue =  (locationStart == null || !locationStart.after(startDate)) &&
                (locationEnd == null || !locationEnd.before(endDate));

        return returnValue;
    }

    private Optional<NhsnLocation> getNhsnLocation(List<NhsnLocation> nhsnLocations, List<String> aliases) {

        // TODO: Need to verify this mapping w/ NDMS
        // At first I was looking first for one of the aliases in the "Unit Label" column.
        // Then for those returned columns further looking for one of the aliases in the
        // "Your Code" column.  Digging around in Bellevue data on 20-Oct-2024 it almost
        // appears that it could be an either or situation?  So the commented out code
        // below is from pre 20-October-2024.  Now going to try either Unit Label or
        // Your Code.
//        List<NhsnLocation> locationsByUnitLabel = nhsnLocations.stream()
//                .filter(location -> aliases.contains(location.getUnit()))
//                .collect(Collectors.toList());
//
//        if (!locationsByUnitLabel.isEmpty()) {
//            return locationsByUnitLabel.stream()
//                    .filter(location -> aliases.contains(location.getCode()))
//                    .findFirst();
//        }


        List<NhsnLocation> byUnit = nhsnLocations.stream()
                .filter(
                        location -> aliases.contains(location.getUnit()) && location.getStatus().equals("A")
                )
                .collect(Collectors.toList());

        List<NhsnLocation> byCode = nhsnLocations.stream()
                .filter(location -> aliases.contains(location.getCode()) && location.getStatus().equals("A"))
                .collect(Collectors.toList());

        if (!byCode.isEmpty()) {
            return Optional.of(byCode.get(0));
        }

        if (!byUnit.isEmpty()) {
            return Optional.of(byUnit.get(0));
        }

        return Optional.empty();
    }

    private CodeableConcept getTrac2esCodeableConcept(String evaluationServiceLocation, String codeSystemLocation, String trac2esCode) {

        // Here we take the TRAC2ES code which we got from looking up the NDMS/BEL code
        // And lookup the full Coding information from the configured TRAC2ES CodeSystem
        // which we are going to assume is loaded on the CQF Evaluation server
        // REFACTOR

        // TODO - need a default "no map" concept.
        CodeableConcept codeableConcept = null;

        // Pull down the CodeSystem if necessary
        if ((trac2esCodeSystem == null) || trac2esCodeSystem.isEmpty()) {
            FhirDataProvider evaluationService = new FhirDataProvider(evaluationServiceLocation);
            trac2esCodeSystem = evaluationService.getCodeSystemById(codeSystemLocation);
        }

        for (CodeSystem.ConceptDefinitionComponent concept : trac2esCodeSystem.getConcept()) {
            if (concept.getCode().equals(trac2esCode)) {
                Coding coding = new Coding();
                coding.setCode(concept.getCode());
                coding.setDisplay(concept.getDisplay());
                coding.setSystem(trac2esCodeSystem.getUrl());
                codeableConcept = new CodeableConcept(coding);
                codeableConcept.addCoding(coding);
            }
        }

        return codeableConcept;
    }

    private List<Location> getLocationsForEncounter(Encounter encounter, Bundle bundle) {
        return encounter.getLocation().stream()
                .map(encounterLocation -> encounterLocation.getLocation().getReference()) // Get the reference strings
                .filter(Objects::nonNull)
                .map(reference -> findLocationInBundle(reference, bundle)) // Look up each reference in the bundle
                .filter(Objects::nonNull) // Filter out any locations we couldn't find
                .distinct()
                .collect(Collectors.toList());
    }

    private Location findLocationInBundle(String reference, Bundle bundle) {
        return bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(Location.class::isInstance)
                .map(Location.class::cast)
                .filter(location -> reference.endsWith(location.getId()))
                .findFirst()
                .orElse(null);
    }
}

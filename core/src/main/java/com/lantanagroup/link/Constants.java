package com.lantanagroup.link;

import org.hl7.fhir.r4.model.Coding;

public class Constants {
  public static final String MainSystem = "https://nhsnlink.org";
  public static final String MHLSystem = "https://mhl.lantanagroup.com";
  public static final String ReportDefinitionTag = "report-definition";
  public static final String LoincSystemUrl = "http://loinc.org";
  public static final String DocRefCode = "55186-1";
  public static final String DocRefDisplay = "Measure Document";
  public static final String ReportPositionExtUrl = "http://hl7.org/fhir/uv/saner/StructureDefinition/GeoLocation";
  public static final String LinkUserTag = "link-user";
  public static final String NotesUrl = "https://www.cdc.gov/nhsn/fhir/nhsnlink/StructureDefinition/nhsnlink-report-note";
  public static final String ExcludedPatientExtUrl = "https://www.cdc.gov/nhsn/fhir/nhsnlink/StructureDefinition/nhsnlink-excluded-patient";
  public static final String DocumentReferenceVersionUrl = "https://www.cdc.gov/nhsn/fhir/nhsnlink/StructureDefinition/nhsnlink-report-version";
  public static final String Roles = "roles";
  public static final String FhirResourcesPackageName = "org.hl7.fhir.r4.model.";
  public static final String UuidPrefix = "urn:uuid:";
  public static final String ApplicablePeriodExtensionUrl = "https://www.lantanagroup.com/fhir/StructureDefinition/link-patient-list-applicable-period";
  public static final String QiCoreOrganizationProfileUrl = "http://hl7.org/fhir/us/qicore/StructureDefinition/qicore-organization";
  public static final String QiCorePatientProfileUrl = "http://hl7.org/fhir/us/qicore/StructureDefinition/qicore-patient";
  public static final String UsCoreEncounterProfileUrl = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-encounter";
  public static final String UsCoreMedicationRequestProfileUrl = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-medicationrequest";
  public static final String UsCoreMedicationProfileUrl = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-medication";
  public static final String UsCoreConditionProfileUrl = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition";
  public static final String UsCoreObservationProfileUrl = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-observation-lab";
  public static final String ReportBundleProfileUrl = "http://lantanagroup.com/fhir/nhsn-measures/StructureDefinition/nhsn-measurereport-bundle";
  public static final String IndividualMeasureReportProfileUrl = "http://lantanagroup.com/fhir/nhsn-measures/StructureDefinition/individual-measure-report";
  public static final String CensusProfileUrl = "http://lantanagroup.com/fhir/nhsn-measures/StructureDefinition/poi-list";
  public static final String MHLReportBundleProfileUrl = "http://lantanagroup.com/fhir/nih-measures/StructureDefinition/nih-measurereport-bundle";
  public static final String MHLIndividualMeasureReportProfileUrl = "http://lantanagroup.com/fhir/nih-measures/StructureDefinition/individual-measure-report";
  public static final String MHLCensusProfileUrl = "http://lantanagroup.com/fhir/nih-measures/StructureDefinition/poi-list";
  public static final String NationalProviderIdentifierSystemUrl = "http://hl7.org.fhir/sid/us-npi";
  public static final String IdentifierSystem = "urn:ietf:rfc:3986";
  public static final String TerminologyEndpointCode = "hl7-fhir-rest";
  public static final String TerminologyEndpointSystem = "http://terminology.hl7.org/CodeSystem/endpoint-connection-type";
  public static final String ConceptMappingExtension = "https://www.lantanagroup.com/fhir/StructureDefinition/mapped-concept";
  public static final String ExtensionCriteriaReference = "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/extension-criteriaReference";
  public static final String MeasuredValues = "http://hl7.org/fhir/uv/saner/CodeSystem/MeasuredValues";
  public static final String OriginalEncounterStatus = "https://www.lantanagroup.com/fhir/StructureDefinition/nhsn-encounter-original-status";
  public static final String ExtensionSupplementalData = "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/extension-supplementalData";
  public static final String patientDataTag = "patient-data";
  public static final String MeasureReportBundleProfileUrl = "https://www.lantanagroup.com/fhir/StructureDefinition/measure-report-bundle";
  public static final String LINK_VERSION_URL = "https://www.cdc.gov/nhsn/fhir/nhsnlink/StructureDefinition/link-version";
  public static final String MEASURE_VERSION_URL = "https://www.cdc.gov/nhsn/fhir/nhsnlink/StructureDefinition/measure-version";
  public static final String LinkUser = "link-user";
  public static final String SANER_JOB_TYPE_SYSTEM = "https://thsa1.sanerproject.org:10443/fhir/ValueSet/saner-job-types";
  public static final Coding EXPUNGE_TASK = new Coding().setCode("expunge-data").setSystem(SANER_JOB_TYPE_SYSTEM).setDisplay("Expunge Data");
  public static final Coding MANUAL_EXPUNGE = new Coding().setCode("manual-expunge").setSystem(SANER_JOB_TYPE_SYSTEM).setDisplay("Manual Expunge");
  public static final Coding REFRESH_PATIENT_LIST = new Coding().setCode("refresh-patient-list").setSystem(SANER_JOB_TYPE_SYSTEM).setDisplay("Refresh Patient List");
  public static final Coding GENERATE_REPORT = new Coding().setCode("generate-report").setSystem(SANER_JOB_TYPE_SYSTEM).setDisplay("Generate Report");
  public static final Coding FILE_UPLOAD = new Coding().setCode("file-upload").setSystem(SANER_JOB_TYPE_SYSTEM).setDisplay("Upload Data File");
  public static final Coding EXTERNAL_FILE_DOWNLOAD = new Coding().setCode("external-file-download").setSystem(SANER_JOB_TYPE_SYSTEM).setDisplay("File Downloaded From Source");
  public static final Coding SEND_REPORT  = new Coding().setCode("send-report").setSystem(SANER_JOB_TYPE_SYSTEM).setDisplay("Send Report");
  public static final String DOCUMENT_REFERENCE_VERSION_URL = "https://www.cdc.gov/nhsn/fhir/nhsnlink/StructureDefinition/nhsnlink-report-version";
}
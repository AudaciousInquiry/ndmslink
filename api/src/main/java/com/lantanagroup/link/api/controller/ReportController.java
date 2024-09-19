package com.lantanagroup.link.api.controller;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import com.lantanagroup.link.Constants;
import com.lantanagroup.link.*;
import com.lantanagroup.link.api.ReportGenerator;
import com.lantanagroup.link.auth.LinkCredentials;
import com.lantanagroup.link.config.api.ApiMeasurePackage;
import com.lantanagroup.link.config.query.QueryConfig;
import com.lantanagroup.link.config.query.USCoreConfig;
import com.lantanagroup.link.model.*;
import com.lantanagroup.link.query.IQuery;
import com.lantanagroup.link.query.QueryFactory;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;


@RestController
@RequestMapping("/api/report")
public class ReportController extends BaseController {
  private static final Logger logger = LoggerFactory.getLogger(ReportController.class);
  private static final String PeriodStartParamName = "periodStart";
  private static final String PeriodEndParamName = "periodEnd";
  // Disallow binding of sensitive attributes
  // Ex: DISALLOWED_FIELDS = new String[]{"details.role", "details.age", "is_admin"};
  final String[] DISALLOWED_FIELDS = new String[]{};
  @Autowired
  private USCoreConfig usCoreConfig;

  @Setter
  @Autowired
  private EventService eventService;

  @Autowired
  @Setter
  private ApplicationContext context;

  @Autowired
  private StopwatchManager stopwatchManager;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @PreDestroy
  public void shutdown() {
    // needed to avoid resource leak
    executor.shutdown();
  }

  @InitBinder
  public void initBinder(WebDataBinder binder) {
    binder.setDisallowedFields(DISALLOWED_FIELDS);
  }

  private void resolveMeasures(ReportCriteria criteria, ReportContext context) throws Exception {
    context.getMeasureContexts().clear();
    for (String bundleId : criteria.getBundleIds()) {

      // Pull the report definition bundle from CQF (eval service)
      FhirDataProvider evaluationProvider = new FhirDataProvider(config.getEvaluationService());
      Bundle reportDefBundle = evaluationProvider.getBundleById(bundleId);

      // Update the context
      ReportContext.MeasureContext measureContext = new ReportContext.MeasureContext();
      measureContext.setReportDefBundle(reportDefBundle);
      measureContext.setBundleId(reportDefBundle.getIdElement().getIdPart());
      Measure measure = FhirHelper.getMeasure(reportDefBundle);
      measureContext.setMeasure(measure);
      context.getMeasureContexts().add(measureContext);
    }
  }

  private void getPatientIdentifiers(ReportCriteria criteria, ReportContext context) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
    List<PatientOfInterestModel> patientOfInterestModelList;

    // TODO: When would the following condition ever be true?
    //       In the standard report generation pipeline, census lists haven't been retrieved by the time we get here
    //       Are we guarding against a case where a BeforePatientOfInterestLookup handler might have done so?
    //       (But wouldn't it be more appropriate to plug that logic in as the patient ID resolver?)
    if (context.getPatientCensusLists() != null && context.getPatientCensusLists().size() > 0) {
      patientOfInterestModelList = new ArrayList<>();
      for (ListResource censusList : context.getPatientCensusLists()) {
        for (ListResource.ListEntryComponent censusPatient : censusList.getEntry()) {
          PatientOfInterestModel patient = new PatientOfInterestModel(
                  censusPatient.getItem().getReference(),
                  IdentifierHelper.toString(censusPatient.getItem().getIdentifier()));
          patientOfInterestModelList.add(patient);
        }
      }
    } else {
      IPatientIdProvider provider;
      Class<?> patientIdResolverClass = Class.forName(this.config.getPatientIdResolver());
      Constructor<?> patientIdentifierConstructor = patientIdResolverClass.getConstructor();
      provider = (IPatientIdProvider) patientIdentifierConstructor.newInstance();
      patientOfInterestModelList = provider.getPatientsOfInterest(criteria, context, this.config);
    }
  }

  private void queryAndStorePatientData(List<String> resourceTypes, ReportCriteria criteria, ReportContext context) throws Exception {
    List<PatientOfInterestModel> patientsOfInterest = context.getPatientsOfInterest();
    List<String> measureIds = context.getMeasureContexts().stream()
            .map(measureContext -> measureContext.getMeasure().getIdentifierFirstRep().getValue())
            .collect(Collectors.toList());
    try {
      // Get the data
      logger.info("Querying/scooping data for the patients: " + StringUtils.join(patientsOfInterest, ", "));
      QueryConfig queryConfig = this.context.getBean(QueryConfig.class);
      IQuery query = QueryFactory.getQueryInstance(this.context, queryConfig.getQueryClass());
      query.execute(criteria, context, patientsOfInterest, context.getMasterIdentifierValue(), resourceTypes, measureIds);
    } catch (Exception ex) {
      logger.error(String.format("Error scooping/storing data for the patients (%s)", StringUtils.join(patientsOfInterest, ", ")));
      throw ex;
    }
  }

  @PostMapping("/$generate")
  public ResponseEntity<?> generateReport(@AuthenticationPrincipal LinkCredentials user,
                                          HttpServletRequest request,
                                          @RequestBody GenerateRequest input) {

    if (input.getBundleIds().length < 1) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("At least one bundleId should be specified.");
    }

    // We want to go ahead here and see if a report with the identifier this criteria would generate already
    // exists.  If so but the regenerate flag isn't set then we want to fail with a 409, which is the legacy
    // behavior expected by the Web component.
    ReportCriteria criteria = new ReportCriteria(List.of(input.getBundleIds()), input.getPeriodStart(), input.getPeriodEnd());
    String masterIdentifierValue = ReportIdHelper.getMasterIdentifierValue(criteria);
    // search by masterIdentifierValue to uniquely identify the document - searching by combination of identifiers could return multiple documents
    // like in the case one document contains the subset of identifiers of what other document contains
    DocumentReference existingDocumentReference = this.getFhirDataProvider().findDocRefForReport(masterIdentifierValue);
    // Search the reference document by measure criteria and reporting period
    if (existingDocumentReference != null && !input.isRegenerate()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A report has already been generated for the specified measure and reporting period. To regenerate the report, submit your request with regenerate=true.");
    }

    Task task = TaskHelper.getNewTask(user, Constants.GENERATE_REPORT);
    FhirDataProvider fhirDataProvider = getFhirDataProvider();
    fhirDataProvider.updateResource(task);
    Job job = new Job(task);

    // Update generateResponse to take Task id (or overload in some way)
    executor.submit(() -> generateResponse(user, request, input.getBundleIds(), input.getPeriodStart(), input.getPeriodEnd(), input.isRegenerate(), job.getId()));

    return ResponseEntity.ok(job);
  }

  /**
   * to be invoked when only a multiMeasureBundleId is provided
   *
   * @return Returns a GenerateResponse
   * @throws Exception
   */
  @PostMapping("/$generateMultiMeasure")
  public GenerateResponse generateReport(
          @AuthenticationPrincipal LinkCredentials user,
          HttpServletRequest request,
          @RequestParam("multiMeasureBundleId") String multiMeasureBundleId,
          @RequestParam("periodStart") String periodStart,
          @RequestParam("periodEnd") String periodEnd,
          boolean regenerate)
          throws Exception {
    String[] singleMeasureBundleIds = new String[]{};

    // should we look for multiple multimeasureid in the configuration file just in case there is a configuration mistake and error out?
    Optional<ApiMeasurePackage> apiMeasurePackage = Optional.empty();
    for (ApiMeasurePackage multiMeasurePackage : config.getMeasurePackages()) {
      if (multiMeasurePackage.getId().equals(multiMeasureBundleId)) {
        apiMeasurePackage = Optional.of(multiMeasurePackage);
        break;
      }
    }
    // get the associated bundle-ids
    if (!apiMeasurePackage.isPresent()) {
      throw new IllegalStateException(String.format("Multimeasure %s is not set-up.", multiMeasureBundleId));
    }
    singleMeasureBundleIds = apiMeasurePackage.get().getBundleIds();

    Task response = TaskHelper.getNewTask(user, Constants.GENERATE_REPORT);
    FhirDataProvider fhirDataProvider = getFhirDataProvider();
    fhirDataProvider.updateResource(response);

    return generateResponse(user, request, singleMeasureBundleIds, periodStart, periodEnd, regenerate, response.getId());
  }

  /**
   * generates a response with one or multiple reports
   */
  private GenerateResponse generateResponse(LinkCredentials user, HttpServletRequest request, String[] bundleIds, String periodStart, String periodEnd, boolean regenerate, String taskId) throws Exception {

    GenerateResponse response = new GenerateResponse();

    // Get the task so that it can be updated later
    FhirDataProvider dataProvider = getFhirDataProvider();
    Task task = dataProvider.getTaskById(taskId);

    try {

      // Add parameters used to generate report to Task
      Annotation note = new Annotation();
      note.setTime(new Date());
      note.setText(String.format("Report being generated with paramters: periodStart - %s / periodEnd - %s / regenerate - %s / bundleIds - %s",
              periodStart,
              periodEnd,
              regenerate,
              String.join(",", bundleIds)));
      task.addNote(note);


      ReportCriteria criteria = new ReportCriteria(List.of(bundleIds), periodStart, periodEnd);
      ReportContext reportContext = new ReportContext(this.getFhirDataProvider());

      reportContext.setRequest(request);
      reportContext.setUser(user);

      this.eventService.triggerEvent(EventTypes.BeforeMeasureResolution, criteria, reportContext);

      // Get the latest measure def and update it on the FHIR storage server
      this.resolveMeasures(criteria, reportContext);

      this.eventService.triggerEvent(EventTypes.AfterMeasureResolution, criteria, reportContext);

      String masterIdentifierValue = ReportIdHelper.getMasterIdentifierValue(criteria);

      // Add note to Task
      note = new Annotation();
      note.setTime(new Date());
      note.setText(String.format("Generating report with identifier: %s", masterIdentifierValue));
      task.addNote(note);

      // TODO - add masterIdentifierValue to Task

      // Search the reference document by measure criteria nd reporting period
      // searching by combination of identifiers could return multiple documents
      // like in the case one document contains the subset of identifiers of what other document contains
//    DocumentReference existingDocumentReference = this.getFhirDataProvider().findDocRefByMeasuresAndPeriod(
//            reportContext.getMeasureContexts().stream()
//                    .map(measureContext -> measureContext.getReportDefBundle().getIdentifier())
//                    .collect(Collectors.toList()),
//            periodStart,
//            periodEnd);

      // search by masterIdentifierValue to uniquely identify the document - searching by combination of identifiers could return multiple documents
      // like in the case one document contains the subset of identifiers of what other document contains
      DocumentReference existingDocumentReference = this.getFhirDataProvider().findDocRefForReport(masterIdentifierValue);
      // Search the reference document by measure criteria and reporting period
      if (existingDocumentReference != null && !regenerate) {
        // A check for this exists in generateReport() now, which means we 'should not' be getting here in this
        // function.  Leaving here just in case... will result in a failed Job for this generate-report run.
        throw new ResponseStatusException(HttpStatus.CONFLICT, "A report has already been generated for the specified measure and reporting period. To regenerate the report, submit your request with regenerate=true.");
      }

      if (existingDocumentReference != null) {
        existingDocumentReference = FhirHelper.incrementMinorVersion(existingDocumentReference);
      }

      // Generate the master report id
      if (!regenerate || existingDocumentReference == null) {
        // generate master report id based on the report date range and the bundles used in the report generation
        reportContext.setMasterIdentifierValue(masterIdentifierValue);
      } else {
        reportContext.setMasterIdentifierValue(existingDocumentReference.getMasterIdentifier().getValue());
        this.eventService.triggerEvent(EventTypes.OnRegeneration, criteria, reportContext);
      }

      this.eventService.triggerEvent(EventTypes.BeforePatientOfInterestLookup, criteria, reportContext);

      // Get the patient identifiers for the given date
      getPatientIdentifiers(criteria, reportContext);

      // Add Lists(s) being process for report to Task
      List<String> listsIds = new ArrayList<>();
      for (ListResource lr : reportContext.getPatientCensusLists()) {
        listsIds.add(lr.getIdElement().getIdPart());
      }
      note = new Annotation();
      note.setTime(new Date());
      note.setText(String.format("Patient Census Lists processed: %s", String.join(",", listsIds)));
      task.addNote(note);

      this.eventService.triggerEvent(EventTypes.AfterPatientOfInterestLookup, criteria, reportContext);

      this.eventService.triggerEvent(EventTypes.BeforePatientDataQuery, criteria, reportContext);

      // Get the resource types to query
      Set<String> resourceTypesToQuery = new HashSet<>();
      for (ReportContext.MeasureContext measureContext : reportContext.getMeasureContexts()) {
        resourceTypesToQuery.addAll(FhirHelper.getDataRequirementTypes(measureContext.getReportDefBundle()));
      }
      // TODO: Fail if there are any data requirements that aren't listed as patient resource types?
      //       How do we expect to accurately evaluate the measure if we can't provide all of its data requirements?
      resourceTypesToQuery.retainAll(usCoreConfig.getPatientResourceTypes());

      // Add list of Resource types that we are going to query to the Task
      note = new Annotation();
      note.setTime(new Date());
      note.setText(String.format("Report being generated by querying these Resource types: %s", String.join(",", resourceTypesToQuery)));
      task.addNote(note);

      // Scoop the data for the patients and store it
      if (config.isSkipQuery()) {
        logger.info("Skipping query and store");
        for (PatientOfInterestModel patient : reportContext.getPatientsOfInterest()) {
          if (patient.getReference() != null) {
            patient.setId(patient.getReference().replaceAll("^Patient/", ""));
          }
        }
      } else {
        this.queryAndStorePatientData(new ArrayList<>(resourceTypesToQuery), criteria, reportContext);
      }

      // TODO: Move this to just after the AfterPatientOfInterestLookup trigger
      if (reportContext.getPatientCensusLists().size() < 1 || reportContext.getPatientCensusLists() == null) {
        String msg = "A census for the specified criteria was not found.";
        logger.error(msg);
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
      }

      this.eventService.triggerEvent(EventTypes.AfterPatientDataQuery, criteria, reportContext);

      response.setMasterId(reportContext.getMasterIdentifierValue());

      this.getFhirDataProvider().audit(request, user.getJwt(), FhirHelper.AuditEventTypes.InitiateQuery, "Successfully Initiated Query");

      for (ReportContext.MeasureContext measureContext : reportContext.getMeasureContexts()) {

        measureContext.setReportId(ReportIdHelper.getMasterMeasureReportId(reportContext.getMasterIdentifierValue(), measureContext.getBundleId()));

        response.setMeasureHashId(ReportIdHelper.hash(measureContext.getBundleId()));

        String reportAggregatorClassName = FhirHelper.getReportAggregatorClassName(config, measureContext.getReportDefBundle());

        IReportAggregator reportAggregator = (IReportAggregator) context.getBean(Class.forName(reportAggregatorClassName));

        ReportGenerator generator = new ReportGenerator(this.stopwatchManager, reportContext, measureContext, criteria, config, user, reportAggregator);

        this.eventService.triggerEvent(EventTypes.BeforeMeasureEval, criteria, reportContext, measureContext);

        generator.generate();

        this.eventService.triggerEvent(EventTypes.AfterMeasureEval, criteria, reportContext, measureContext);

        this.eventService.triggerEvent(EventTypes.BeforeReportStore, criteria, reportContext, measureContext);

        generator.store();

        this.eventService.triggerEvent(EventTypes.AfterReportStore, criteria, reportContext, measureContext);

      }

      DocumentReference documentReference = this.generateDocumentReference(criteria, reportContext);

      if (existingDocumentReference != null) {
        documentReference.setId(existingDocumentReference.getId());

        Extension existingVersionExt = existingDocumentReference.getExtensionByUrl(Constants.DocumentReferenceVersionUrl);
        String existingVersion = existingVersionExt.getValue().toString();

        documentReference.getExtensionByUrl(Constants.DocumentReferenceVersionUrl).setValue(new StringType(existingVersion));

        documentReference.setContent(existingDocumentReference.getContent());
      } else {
        // generate document reference id based on the report date range and the measure used in the report generation
        UUID documentId = UUID.nameUUIDFromBytes(reportContext.getMasterIdentifierValue().getBytes(StandardCharsets.UTF_8));
        documentReference.setId(documentId.toString());
      }

      // Add the patient census list(s) to the document reference
      documentReference.getContext().getRelated().clear();
      documentReference.getContext().getRelated().addAll(reportContext.getPatientCensusLists().stream().map(censusList -> new Reference()
              .setReference("List/" + censusList.getIdElement().getIdPart())).collect(Collectors.toList()));

      this.getFhirDataProvider().updateResource(documentReference);

      this.getFhirDataProvider().audit(request, user.getJwt(), FhirHelper.AuditEventTypes.Generate, "Successfully Generated Report");
      logger.info(String.format("Done generating report %s", documentReference.getIdElement().getIdPart()));

      this.stopwatchManager.print();
      this.stopwatchManager.reset();

      // Update Task - TODO - pass this down farther for more notes, catch errors, etc...
      //reportContext.getPatientCensusLists().get(0).getIdElement().getIdPart()
      task.setStatus(Task.TaskStatus.COMPLETED);
      note = new Annotation();
      note.setTime(new Date());
      note.setText("Done generating report.");
      task.addNote(note);
    } catch (Exception ex) {
      String errorMessage = String.format("Issue with report generation: %s", ex.getMessage());
      logger.error(errorMessage);
      Annotation note = new Annotation();
      note.setText(errorMessage);
      note.setTime(new Date());
      task.addNote(note);
      task.setStatus(Task.TaskStatus.FAILED);
      throw ex;
    } finally {
      task.setLastModified(new Date());
      dataProvider.updateResource(task);
    }

    return response;
  }

  private DocumentReference generateDocumentReference(ReportCriteria reportCriteria, ReportContext reportContext) throws ParseException {
    DocumentReference documentReference = new DocumentReference();
    Identifier identifier = new Identifier();
    identifier.setSystem(config.getDocumentReferenceSystem());
    identifier.setValue(reportContext.getMasterIdentifierValue());

    documentReference.setMasterIdentifier(identifier);
    for (ReportContext.MeasureContext measureContext : reportContext.getMeasureContexts()) {
      documentReference.addIdentifier().setSystem(Constants.MainSystem).setValue(measureContext.getBundleId());
    }

    documentReference.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);

    List<Reference> list = new ArrayList<>();
    Reference reference = new Reference();
    if (reportContext.getUser() != null && reportContext.getUser().getPractitioner() != null) {
      String practitionerId = reportContext.getUser().getPractitioner().getId();
      if (StringUtils.isNotEmpty(practitionerId)) {
        reference.setReference(practitionerId.substring(practitionerId.indexOf("Practitioner"), practitionerId.indexOf("_history") - 1));
        list.add(reference);
        documentReference.setAuthor(list);
      }
    }

    documentReference.setDocStatus(DocumentReference.ReferredDocumentStatus.PRELIMINARY);

    CodeableConcept type = new CodeableConcept();
    List<Coding> codings = new ArrayList<>();
    Coding coding = new Coding();
    coding.setCode(Constants.DocRefCode);
    coding.setSystem(Constants.LoincSystemUrl);
    coding.setDisplay(Constants.DocRefDisplay);
    codings.add(coding);
    type.setCoding(codings);
    documentReference.setType(type);

    List<DocumentReference.DocumentReferenceContentComponent> listDoc = new ArrayList<>();
    DocumentReference.DocumentReferenceContentComponent doc = new DocumentReference.DocumentReferenceContentComponent();
    Attachment attachment = new Attachment();
    attachment.setCreation(new Date());
    doc.setAttachment(attachment);
    listDoc.add(doc);
    documentReference.setContent(listDoc);

    DocumentReference.DocumentReferenceContextComponent docReference = new DocumentReference.DocumentReferenceContextComponent();
    Period period = new Period();
    Date startDate = Helper.parseFhirDate(reportCriteria.getPeriodStart());
    Date endDate = Helper.parseFhirDate(reportCriteria.getPeriodEnd());
    period.setStartElement(new DateTimeType(startDate, TemporalPrecisionEnum.MILLI, TimeZone.getDefault()));
    period.setEndElement(new DateTimeType(endDate, TemporalPrecisionEnum.MILLI, TimeZone.getDefault()));

    docReference.setPeriod(period);

    documentReference.setContext(docReference);

    documentReference.addExtension(FhirHelper.createVersionExtension("0.1"));

    return documentReference;
  }

  private void sendReport(LinkCredentials user,
                          @PathVariable String reportId,
                          HttpServletRequest request,
                          String taskId) {

    // Get the task so that it can be updated later
    FhirDataProvider dataProvider = getFhirDataProvider();
    Task task = dataProvider.getTaskById(taskId);

    try {

      logger.info("Sending Report with ID {}", reportId);

      Annotation note = new Annotation();
      String noteMessage = "";

      String submitterName = FhirHelper.getName(user.getPractitioner().getName());

      DocumentReference documentReference = this.getFhirDataProvider().findDocRefForReport(reportId);
      noteMessage = String.format("DocumentReference '%s' associated with report retrieved.", documentReference.getIdElement().getIdPart());
      logger.info(noteMessage);
      note = new Annotation();
      note.setTime(new Date());
      note.setText(noteMessage);
      task.addNote(note);

      List<MeasureReport> reports = documentReference.getIdentifier().stream()
              .map(identifier -> ReportIdHelper.getMasterMeasureReportId(reportId, identifier.getValue()))
              .map(id -> this.getFhirDataProvider().getMeasureReportById(id))
              .collect(Collectors.toList());

      Class<?> senderClazz = Class.forName(this.config.getSender());
      IReportSender sender = (IReportSender) this.context.getBean(senderClazz);
      logger.info("Report '{}' being sent using class '{}'", reportId, config.getSender());

      String sentLocation = sender.send(reports, documentReference, request, this.getFhirDataProvider(), bundlerConfig);
      noteMessage = String.format("Report with ID '%s' sent to %s", reportId, sentLocation);
      logger.info(noteMessage);
      note = new Annotation();
      note.setTime(new Date());
      note.setText(noteMessage);
      task.addNote(note);

      // Log / Add Task Note
      noteMessage = String.format("Report with ID %s submitted by %s on %s",
              documentReference.getMasterIdentifier().getValue(),
              (Helper.validateLoggerValue(submitterName) ? submitterName : ""),
              new Date());
      logger.info(noteMessage);
      note = new Annotation();
      note.setTime(new Date());
      note.setText(noteMessage);
      task.addNote(note);

      // Now that we've submitted (successfully), update the doc ref with the status and date
      documentReference.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);
      documentReference.setDate(new Date());
      documentReference = FhirHelper.incrementMajorVersion(documentReference);
      this.getFhirDataProvider().updateResource(documentReference);
      noteMessage = String.format("DocumentReference '%s' updated with Status '%s', Date '%s', and Version '%s'",
              documentReference.getIdElement().getIdPart(),
              documentReference.getStatus(),
              documentReference.getDate(),
              documentReference.getExtensionByUrl(Constants.DOCUMENT_REFERENCE_VERSION_URL).getValue().toString());
      logger.info(noteMessage);
      note = new Annotation();
      note.setTime(new Date());
      note.setText(noteMessage);
      task.addNote(note);

      this.getFhirDataProvider().audit(request, user.getJwt(), FhirHelper.AuditEventTypes.Send, "Successfully Sent Report");

      //reportContext.getPatientCensusLists().get(0).getIdElement().getIdPart()
      task.setStatus(Task.TaskStatus.COMPLETED);
      note = new Annotation();
      note.setTime(new Date());
      note.setText(String.format("Done sending report '%s'", reportId));
      task.addNote(note);
    } catch (Exception ex) {
      String errorMessage = String.format("Issue with sending report '%s' - %s", reportId, ex.getMessage());
      logger.error(errorMessage);
      Annotation note = new Annotation();
      note.setText(errorMessage);
      note.setTime(new Date());
      task.addNote(note);
      task.setStatus(Task.TaskStatus.FAILED);
    } finally {
      task.setLastModified(new Date());
      dataProvider.updateResource(task);
    }

  }

  /**
   * Sends the specified report to the recipients configured in <strong>api.send-urls</strong>
   *
   * @param reportId - this is the report identifier after generate report was clicked
   * @param request
   * @throws Exception Thrown when the configured sender class is not found or fails to initialize or the reportId it not found
   */
  @PostMapping("/{reportId}/$send")
  public ResponseEntity<?> send(
          @AuthenticationPrincipal LinkCredentials user,
          @PathVariable String reportId,
          HttpServletRequest request){

    if (StringUtils.isEmpty(this.config.getSender())) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Not configured for sending");
    }

    Task task = TaskHelper.getNewTask(user, Constants.SEND_REPORT);
    FhirDataProvider fhirDataProvider = getFhirDataProvider();
    fhirDataProvider.updateResource(task);

    Job job = new Job(task);

    executor.submit(() -> sendReport(user, reportId, request, task.getId()));

    return ResponseEntity.ok(job);
  }

  @GetMapping("/{reportId}/$download/{type}")
  public void download(
          @PathVariable String reportId,
          @PathVariable String type,
          HttpServletResponse response,
          Authentication authentication,
          HttpServletRequest request) throws Exception {

    if (StringUtils.isEmpty(this.config.getDownloader()))
      throw new IllegalStateException("Not configured for downloading");

    IReportDownloader downloader;
    Class<?> downloaderClass = Class.forName(this.config.getDownloader());
    Constructor<?> downloaderCtor = downloaderClass.getConstructor();
    downloader = (IReportDownloader) downloaderCtor.newInstance();

    downloader.download(reportId, type, this.getFhirDataProvider(), response, this.ctx, this.bundlerConfig, this.eventService);

    this.getFhirDataProvider().audit(request, ((LinkCredentials) authentication.getPrincipal()).getJwt(), FhirHelper.AuditEventTypes.Export, "Successfully Exported Report for Download");
  }

  @GetMapping(value = "/{reportId}")
  public ReportModel getReport(
          @PathVariable("reportId") String reportId) throws Exception {

    ReportModel reportModel = new ReportModel();
    List<ReportModel.ReportMeasure> reportModelList = new ArrayList<>();
    reportModel.setReportMeasureList(reportModelList);
    DocumentReference documentReference = this.getFhirDataProvider().findDocRefForReport(reportId);

    for (int i = 0; i < documentReference.getIdentifier().size(); i++) {
      String encodedReport = "";
      //prevent injection from reportId parameter
      try {
        encodedReport = Helper.encodeForUrl(ReportIdHelper.getMasterMeasureReportId(reportId, documentReference.getIdentifier().get(i).getValue()));
      } catch (Exception ex) {
        logger.error(ex.getMessage());
      }

      // Get the Measure to put in Report from Evaluation Service, where it is actually being used
      // and not the old version pulled from report defs in the config
      FhirDataProvider evaluationService = new FhirDataProvider(this.config.getEvaluationService());
      Measure measure = evaluationService.getMeasureById(documentReference.getIdentifier().get(i).getValue());

      ReportModel.ReportMeasure reportMeasure = new ReportModel.ReportMeasure();
      // get Master Measure Report
      reportMeasure.setMeasureReport(this.getFhirDataProvider().getMeasureReportById(encodedReport));
      reportMeasure.setBundleId(measure.getIdElement().getIdPart());
      reportMeasure.setMeasure(measure);
      reportModel.setVersion(documentReference
              .getExtensionByUrl(Constants.DocumentReferenceVersionUrl) != null ?
              documentReference.getExtensionByUrl(Constants.DocumentReferenceVersionUrl).getValue().toString() : null);
      reportModel.setStatus(documentReference.getDocStatus().toString());
      reportModel.setDate(documentReference.getDate());
      reportModelList.add(reportMeasure);
      reportModel.setReportPeriodStart(documentReference.getContext().getPeriod().getStart());
      reportModel.setReportPeriodEnd(documentReference.getContext().getPeriod().getEnd());
    }
    return reportModel;
  }


  @GetMapping(value = "/{reportId}/patient")
  public List<PatientReportModel> getReportPatients(
          @PathVariable("reportId") String reportId) throws Exception {
    throw new Exception("Get Patient Reports Not Implemented");
  }


  @PutMapping(value = "/{id}")
  public void saveReport(
          @PathVariable("id") String id,
          Authentication authentication,
          HttpServletRequest request,
          @RequestBody ReportSaveModel data) throws Exception {

    // TODO
    // ALM 15May2023 - again faced with getting from UI MasterReportId+MeasureId but
    // generate DOES NOT store DocumentReference with this id.
    String masterReportId = id;
    String[] idParts = id.split("-");
    if (idParts.length > 1) {
      masterReportId = idParts[0];
    }

    DocumentReference documentReference = this.getFhirDataProvider().findDocRefForReport(masterReportId);

    documentReference = FhirHelper.incrementMinorVersion(documentReference);

    this.getFhirDataProvider().updateResource(documentReference);
    this.getFhirDataProvider().updateResource(data.getMeasureReport());

    // TODO: Wrong audit event type? We're saving the report, not sending it
    this.getFhirDataProvider().audit(request, ((LinkCredentials) authentication.getPrincipal()).getJwt(),
            FhirHelper.AuditEventTypes.Send, "Successfully updated MeasureReport with id: " +
                    documentReference.getMasterIdentifier().getValue());
  }

  /**
   * Retrieves data (encounters, conditions, etc.) for the specified patient within the specified report.
   *
   * @param reportId       The report id
   * @param patientId      The patient id within the report
   * @param authentication The authenticated user making the request
   * @param request        The HTTP request
   * @return SubjectReportModel
   * @throws Exception
   */
  @GetMapping(value = "/{reportId}/patient/{patientId}")
  public PatientDataModel getPatientData(
          @PathVariable("reportId") String reportId,
          @PathVariable("patientId") String patientId,
          Authentication authentication,
          HttpServletRequest request) throws Exception {

    PatientDataModel data = new PatientDataModel();
    data.setConditions(new ArrayList<>());
    data.setMedicationRequests(new ArrayList<>());
    data.setProcedures(new ArrayList<>());
    data.setObservations(new ArrayList<>());
    data.setEncounters(new ArrayList<>());
    data.setServiceRequests(new ArrayList<>());

    // TODO - revisit this.
    // ALM 15May2023
    // UI is calling this /api/report/430749b-9d35173c/patient/<REMOVED>
    // This ultimately will call Bundle/430749b-9d35173c-patidhash but nowhere in
    // generate code are we storing Patient bundles under that id... only storing
    // under 430749b-patidhash.
    // So here stripping off the measure hash from the passed in id.
    String masterReportId = reportId;
    String[] idParts = reportId.split("-");
    if (idParts.length > 1) {
      masterReportId = idParts[0];
    }

    List<Bundle> patientBundles = getPatientBundles(masterReportId, patientId);
    if (patientBundles == null || patientBundles.size() < 1) {
      return data;
    }

    for (Bundle patientBundle : patientBundles) {
      for (Bundle.BundleEntryComponent entry : patientBundle.getEntry()) {
        if (entry.getResource() != null && entry.getResource().getResourceType().toString().equals("Condition")) {
          Condition condition = (Condition) entry.getResource();
          if (condition.getSubject().getReference().equals("Patient/" + patientId)) {
            data.getConditions().add(condition);
          }
        }
        if (entry.getResource() != null && entry.getResource().getResourceType().toString().equals("MedicationRequest")) {
          MedicationRequest medicationRequest = (MedicationRequest) entry.getResource();
          if (medicationRequest.getSubject().getReference().equals("Patient/" + patientId)) {
            data.getMedicationRequests().add(medicationRequest);
          }
        }
        if (entry.getResource() != null && entry.getResource().getResourceType().toString().equals("Observation")) {
          Observation observation = (Observation) entry.getResource();
          if (observation.getSubject().getReference().equals("Patient/" + patientId)) {
            data.getObservations().add(observation);
          }
        }
        if (entry.getResource() != null && entry.getResource().getResourceType().toString().equals("Procedure")) {
          Procedure procedure = (Procedure) entry.getResource();
          if (procedure.getSubject().getReference().equals("Patient/" + patientId)) {
            data.getProcedures().add(procedure);
          }
        }
        if (entry.getResource() != null && entry.getResource().getResourceType().toString().equals("Encounter")) {
          Encounter encounter = (Encounter) entry.getResource();
          if (encounter.getSubject().getReference().equals("Patient/" + patientId)) {
            data.getEncounters().add(encounter);
          }
        }
        if (entry.getResource() != null && entry.getResource().getResourceType().toString().equals("ServiceRequest")) {
          ServiceRequest serviceRequest = (ServiceRequest) entry.getResource();
          if (serviceRequest.getSubject().getReference().equals("Patient/" + patientId)) {
            data.getServiceRequests().add(serviceRequest);
          }
        }
      }
    }

    return data;
  }

  @DeleteMapping(value = "/{id}")
  public void deleteReport(
          @PathVariable("id") String id,
          @RequestParam(required = false) Boolean ignoreVersionCheck,
          Authentication authentication,
          HttpServletRequest request) throws Exception {
    Bundle deleteRequest = new Bundle();

    Boolean skipVersionCheck = ignoreVersionCheck != null ? ignoreVersionCheck : false;

    // TODO - revisit this.
    // ALM 15May2023
    // UI is calling this /api/report/430749b-9d35173c/patient/<REMOVED>
    // This ultimately will call Bundle/430749b-9d35173c-patidhash but nowhere in
    // generate code are we storing Patient bundles under that id... only storing
    // under 430749b-patidhash.
    // So here stripping off the measure hash from the passed in id.
    String masterReportId = id;
    String[] idParts = id.split("-");
    if (idParts.length > 1) {
      masterReportId = idParts[0];
    }

    DocumentReference documentReference = this.getFhirDataProvider().findDocRefForReport(masterReportId);

    Extension existingVersionExt = documentReference.getExtensionByUrl(Constants.DocumentReferenceVersionUrl);
    Float existingVersion = Float.parseFloat(existingVersionExt.getValue().toString());

    // only ignore the version check of ignoreVersionCheck is passed via command line
    if ( (existingVersion >= 1.0f) && (!skipVersionCheck) ) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Report version is greater than or equal to 1.0");
    }

    // Make sure the bundle is a transaction
    deleteRequest.setType(Bundle.BundleType.TRANSACTION);
    deleteRequest.addEntry().setRequest(new Bundle.BundleEntryRequestComponent());
    deleteRequest.addEntry().setRequest(new Bundle.BundleEntryRequestComponent());
    deleteRequest.getEntry().forEach(entry ->
            entry.getRequest().setMethod(Bundle.HTTPVerb.DELETE)
    );
    String documentReferenceId = documentReference.getId();
    documentReferenceId = documentReferenceId.substring(documentReferenceId.indexOf("/DocumentReference/") + "/DocumentReference/".length(),
            documentReferenceId.indexOf("/_history/"));
    deleteRequest.getEntry().get(0).getRequest().setUrl("MeasureReport/" + documentReference.getMasterIdentifier().getValue());
    deleteRequest.getEntry().get(1).getRequest().setUrl("DocumentReference/" + documentReferenceId);
    this.getFhirDataProvider().transaction(deleteRequest);

    this.getFhirDataProvider().audit(request, ((LinkCredentials) authentication.getPrincipal()).getJwt(),
            FhirHelper.AuditEventTypes.Export, "Successfully deleted DocumentReference" +
                    documentReferenceId + " and MeasureReport " + documentReference.getMasterIdentifier().getValue());
  }

  @GetMapping(produces = {MediaType.APPLICATION_JSON_VALUE})
  public ReportBundle searchReports(
          Authentication authentication,
          HttpServletRequest request,
          @RequestParam(required = false, defaultValue = "1") Integer page,
          @RequestParam(required = false) String author,
          @RequestParam(required = false) String identifier,
          @RequestParam(required = false) String periodStartDate,
          @RequestParam(required = false) String periodEndDate,
          @RequestParam(required = false) String docStatus,
          @RequestParam(required = false) String submittedDate,
          @RequestParam(required = false) String submitted)
          throws Exception {

    Bundle bundle;
    boolean andCond = false;
    ReportBundle reportBundle = new ReportBundle();

    String url = this.config.getDataStore().getBaseUrl();
    if (!url.endsWith("/")) url += "/";
    url += "DocumentReference?";
    if (author != null) {
      url += "author=" + author;
      andCond = true;
    }
    if (identifier != null) {
      if (andCond) {
        url += "&";
      }
      url += "identifier=" + Helper.URLEncode(identifier);
      andCond = true;
    }
    if (periodStartDate != null) {
      if (andCond) {
        url += "&";
      }
      url += PeriodStartParamName + "=ge" + periodStartDate;
      andCond = true;
    }
    if (periodEndDate != null) {
      if (andCond) {
        url += "&";
      }
      url += PeriodEndParamName + "=le" + periodEndDate;
      andCond = true;
    }
    if (docStatus != null) {
      if (andCond) {
        url += "&";
      }
      url += "docStatus=" + docStatus.toLowerCase();
      andCond = true;
    }

    Boolean submittedBoolean = null;
    if (submitted != null) {
      submittedBoolean = Boolean.parseBoolean(submitted);
    }

    if (Boolean.TRUE.equals(submittedBoolean)) {
      // We want to find documents that have been submitted.  Which
      // should mean that docStatus = final and the date isn't null.
      // All we can do here is search for docStatus = final then later
      // also verify that the date has a value.
      if (andCond) {
        url += "&";
      }
      url += "docStatus=final";
      andCond = true;
    } else if (Boolean.FALSE.equals(submittedBoolean)) {
      // We want to fnd documents that HAVE NOT been submitted.  Which
      // should mean that docStatus <> final and that the date field is
      // either missing or set to null.  Which we have to check later.
      if (andCond) {
        url += "&";
      }
      url += "_filter=docStatus+ne+final";
      andCond = true;
    }

    if (submittedDate != null) {
      if (andCond) {
        url += "&";
      }
      Date submittedDateAsDate = Helper.parseFhirDate(submittedDate);
      Date theDayAfterSubmittedDateEnd = Helper.addDays(submittedDateAsDate, 1);
      String theDayAfterSubmittedDateEndAsString = Helper.getFhirDate(theDayAfterSubmittedDateEnd);
      url += "date=ge" + submittedDate + "&date=le" + theDayAfterSubmittedDateEndAsString;
    }

    bundle = this.getFhirDataProvider().fetchResourceFromUrl(url);
    List<Report> lst = bundle.getEntry().parallelStream().map(Report::new).collect(Collectors.toList());

    // Remove items from lst if we are searching for submitted only but the date is null
    // Only DocumentReferences that have been submitted will have a value for date.
    if (Boolean.TRUE.equals(submittedBoolean)) {
      lst.removeIf(report -> report.getSubmittedDate() == null);
    }

    // Remove items from lst if we are searching for non-submitted but the date
    // has a value.  Only DocumentReference that have been submitted will have a value for
    // date
    if (Boolean.FALSE.equals(submittedBoolean)) {
      lst.removeIf(report -> report.getSubmittedDate() != null);
    }


    List<String> reportIds = lst.stream().map(report -> ReportIdHelper.getMasterMeasureReportId(report.getId(),report.getReportMeasure().getValue())).collect(Collectors.toList());
    Bundle response = this.getFhirDataProvider().getMeasureReportsByIds(reportIds);

    response.getEntry().parallelStream().forEach(bundleEntry -> {
      if (bundleEntry.getResource().getResourceType().equals(ResourceType.MeasureReport)) {
        MeasureReport measureReport = (MeasureReport) bundleEntry.getResource();
        Extension extension = measureReport.getExtensionByUrl(Constants.NotesUrl);
        Report foundReport = lst.stream().filter(rep -> rep.getId().equals(measureReport.getIdElement().getIdPart().split("-")[0])).findAny().orElse(null);
        if (extension != null && foundReport != null) {
          foundReport.setNote(extension.getValue().toString());
        }
      }
    });
    reportBundle.setReportTypeId(bundle.getId());
    reportBundle.setList(lst);
    reportBundle.setTotalSize(bundle.getTotal());

    this.getFhirDataProvider().audit(request, ((LinkCredentials) authentication.getPrincipal()).getJwt(), FhirHelper.AuditEventTypes.SearchReports, "Successfully Searched Reports");

    return reportBundle;
  }

  /**
   * Retrieves the DocumentReference and MeasureReport, ensures that each of the excluded Patients in the request
   * are listed in the MeasureReport.evaluatedResources or as "excluded" extensions on the MeasureReport. Creates
   * the excluded extension on the MR for each patient, DELETE's each patient. Re-evaluates the MeasureReport against
   * the Measure. Increments the minor version number of the report in DocumentReference. Stores updates to the
   * DR and MR back to the FHIR server.
   *
   * @param authentication   Authentication information to create an IGenericClient to the internal FHIR store
   * @param request          The HTTP request to create an IGenericClient to the internal FHIR store
   * @param user             The user making the request, for the audit trail
   * @param reportId         The ID of the report to re-evaluate after DELETE'ing/excluding the patients.
   * @param excludedPatients A list of patients to be excluded from the report, including reasons for their exclusion
   * @return A ReportModel that has been updated to reflect the exclusions
   */
  @PostMapping("/{reportId}/$exclude")
  public ReportModel excludePatients(
          Authentication authentication,
          HttpServletRequest request,
          @AuthenticationPrincipal LinkCredentials user,
          @PathVariable("reportId") String reportId,
          @RequestBody List<ExcludedPatientModel> excludedPatients) throws Exception {

    DocumentReference reportDocRef = this.getFhirDataProvider().findDocRefForReport(ReportIdHelper.getMasterIdentifierValue(reportId));

    if (reportDocRef == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, String.format("Report %s not found", reportId));
    }

    if (excludedPatients == null || excludedPatients.size() == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No exclusions specified");
    }


    /* ALM 11May2023 from what I have seen in report generation MeasureReports will NEVER exist with just the Report Id
    playing around pulling by masterId and measure hash (THSAMeasure)
     */

    Measure measure = null;
    MeasureReport measureReport = null;
    Bundle measureBundle = null;
    for (int i = 0; i < reportDocRef.getIdentifier().size(); i++) {
      String encodedReport = "";
      try {
        encodedReport = Helper.encodeForUrl(ReportIdHelper.getMasterMeasureReportId(reportId, reportDocRef.getIdentifier().get(i).getValue()));
        measureBundle =  this.getFhirDataProvider().getBundleById(reportDocRef.getIdentifier().get(i).getValue());
        measureReport = this.getFhirDataProvider().getMeasureReportById(encodedReport);
        measure = FhirHelper.getMeasure(measureBundle);
        break;
      } catch (Exception ex) {
        logger.error(ex.getMessage());
      }
    }

    List<String> bundleIds = reportDocRef.getIdentifier().stream().map(identifier -> identifier.getValue()).collect(Collectors.toList());

    if (measure == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, String.format("The measure for report %s was not found or no longer exists on the system", reportId));
    }

    Bundle excludeChangesBundle = new Bundle();
    excludeChangesBundle.setType(Bundle.BundleType.TRANSACTION);
    Boolean changedMeasureReport = false;

    for (ExcludedPatientModel excludedPatient : excludedPatients) {
      if (StringUtils.isEmpty(excludedPatient.getPatientId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format("Patient ID not provided for all exclusions"));
      }

      if (excludedPatient.getReason() == null || excludedPatient.getReason().isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format("Excluded patient ID %s does not specify a reason", excludedPatient.getPatientId()));
      }

      // Find any references to the Patient in the MeasureReport.evaluatedResources
      List<Reference> foundEvaluatedPatient = measureReport.getEvaluatedResource().stream()
              .filter(er -> er.getReference() != null && er.getReference().equals("Patient/" + excludedPatient.getPatientId()))
              .collect(Collectors.toList());
      // Find any extensions that list the Patient has already being excluded
      Boolean foundExcluded = measureReport.getExtension().stream()
              .filter(e -> e.getUrl().equals(Constants.ExcludedPatientExtUrl))
              .anyMatch(e -> e.getExtension().stream()
                      .filter(nextExt -> nextExt.getUrl().equals("patient") && nextExt.getValue() instanceof Reference)
                      .anyMatch(nextExt -> {
                        Reference patientRef = (Reference) nextExt.getValue();
                        return patientRef.getReference().equals("Patient/" + excludedPatient.getPatientId());
                      }));

      // Throw an error if the Patient does not show up in either evaluatedResources or the excluded extensions
      //Commented out until CQF ruler is fixed
      /*if (foundEvaluatedPatient.size() == 0 && !foundExcluded) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format("Patient %s is not included in report %s", excludedPatient.getPatientId(), reportId));
      }*/

      // Create an extension for the excluded patient on the MeasureReport
      //Commented out until CQF ruler is fixed
      /*if (!foundExcluded) {
        Extension newExtension = new Extension(Constants.ExcludedPatientExtUrl);
        newExtension.addExtension("patient", new Reference("Patient/" + excludedPatient.getPatientId()));
        newExtension.addExtension("reason", excludedPatient.getReason());
        measureReport.addExtension(newExtension);
        changedMeasureReport = true;

        // Remove the patient from evaluatedResources, or HAPI will throw a referential integrity exception since it's getting (or has been) deleted
        if (foundEvaluatedPatient.size() > 0) {
          foundEvaluatedPatient.forEach(ep -> measureReport.getEvaluatedResource().remove(ep));
        }
      }*/

      logger.debug(String.format("Checking if patient %s has been deleted already", excludedPatient.getPatientId()));

      try {
        // Try to GET the patient to see if it has already been deleted or not
        this.getFhirDataProvider().tryGetResource("Patient", excludedPatient.getPatientId());
        logger.debug(String.format("Adding patient %s to list of patients to delete", excludedPatient.getPatientId()));

        // Add a "DELETE" request to the bundle, since it hasn't been deleted
        Bundle.BundleEntryRequestComponent deleteRequest = new Bundle.BundleEntryRequestComponent()
                .setUrl("Patient/" + excludedPatient.getPatientId())
                .setMethod(Bundle.HTTPVerb.DELETE);
        excludeChangesBundle.addEntry().setRequest(deleteRequest);
      } catch (Exception ex) {
        // It's been deleted, just log some debugging info
        logger.debug(String.format("During exclusions for report %s, patient %s is already deleted.", reportId, excludedPatient.getPatientId()));
      }
    }

    if (changedMeasureReport) {
      Bundle.BundleEntryRequestComponent updateMeasureReportReq = new Bundle.BundleEntryRequestComponent()
              .setUrl("MeasureReport/" + reportId)
              .setMethod(Bundle.HTTPVerb.PUT);
      excludeChangesBundle.addEntry()
              .setRequest(updateMeasureReportReq)
              .setResource(measureReport);
    }

    if (excludeChangesBundle.getEntry().size() > 0) {
      logger.debug(String.format("Executing transaction update bundle to delete patients and/or update MeasureReport %s", reportId));

      this.getFhirDataProvider().transaction(excludeChangesBundle);
    }

    // Create ReportCriteria to be used by MeasureEvaluator
    ReportCriteria criteria = new ReportCriteria(
            bundleIds,
            reportDocRef.getContext().getPeriod().getStartElement().asStringValue(),
            reportDocRef.getContext().getPeriod().getEndElement().asStringValue());

    // Create ReportContext to be used by MeasureEvaluator
    ReportContext reportContext = new ReportContext(this.getFhirDataProvider());
    reportContext.setRequest(request);
    reportContext.setUser(user);
    reportContext.setMasterIdentifierValue(reportId);
    ReportContext.MeasureContext measureContext = new ReportContext.MeasureContext();
    //measureContext.setBundleId(reportDefIdentifier); // this was commented out.
    // TODO: Set report def bundle on measure context
    measureContext.setReportDefBundle(measureBundle);
    measureContext.setMeasure(measure);
    measureContext.setReportId(measureReport.getIdElement().getIdPart());
    reportContext.getMeasureContexts().add(measureContext);

    logger.debug("Re-evaluating measure with state of data on FHIR server");

    List<String> excludedPatientReportIds = excludedPatients.stream().map(
            patient -> ReportIdHelper.getPatientMeasureReportId(reportId, patient.getPatientId())).collect(Collectors.toList());

    Set<String> reportRefs = measureReport.getContained().stream().map(list -> (ListResource) list).flatMap(list -> list.getEntry().stream().map(
            entry -> entry.getItem().getReference().substring(entry.getItem().getReference().indexOf("/") + 1))).collect(Collectors.toSet());

    measureContext.setPatientReports(reportRefs.stream().filter(ref -> !excludedPatientReportIds.contains(ref)).map(ref -> {
      MeasureReport patientReport = this.getFhirDataProvider().getMeasureReportById(ref); return patientReport;
    }).collect(Collectors.toList()));

    String reportAggregatorClassName = FhirHelper.getReportAggregatorClassName(config, measureBundle);
    IReportAggregator reportAggregator = (IReportAggregator) context.getBean(Class.forName(reportAggregatorClassName));
    MeasureReport updatedMeasureReport = reportAggregator.generate(criteria, reportContext, measureContext);

    updatedMeasureReport.setId(reportId);
    updatedMeasureReport.setExtension(measureReport.getExtension());    // Copy extensions from the original report before overwriting

    // Increment the version of the report
    FhirHelper.incrementMinorVersion(reportDocRef);

    logger.debug(String.format("Updating DocumentReference and MeasureReport for report %s", reportId));

    // Create a bundle transaction to update the DocumentReference and MeasureReport
    Bundle reportUpdateBundle = new Bundle();
    reportUpdateBundle.setType(Bundle.BundleType.TRANSACTION);
    reportUpdateBundle.addEntry()
            .setRequest(
                    new Bundle.BundleEntryRequestComponent()
                            .setUrl("MeasureReport/" + updatedMeasureReport.getIdElement().getIdPart())
                            .setMethod(Bundle.HTTPVerb.PUT))
            .setResource(updatedMeasureReport);
    reportUpdateBundle.addEntry()
            .setRequest(
                    new Bundle.BundleEntryRequestComponent()
                            .setUrl("DocumentReference/" + reportDocRef.getIdElement().getIdPart())
                            .setMethod(Bundle.HTTPVerb.PUT))
            .setResource(reportDocRef);

    // Execute the update transaction bundle for MeasureReport and DocumentReference
    this.getFhirDataProvider().transaction(reportUpdateBundle);

    // Record an audit event that the report has had exclusions
    this.getFhirDataProvider().audit(request, user.getJwt(), FhirHelper.AuditEventTypes.ExcludePatients, String.format("Excluded %s patients from report %s", excludedPatients.size(), reportId));

    // Create the ReportModel that will be returned
    ReportModel report = new ReportModel();
//    report.setMeasureReport(updatedMeasureReport);
//    report.setMeasure(measure);
//    report.setIdentifier(reportId);
    report.setVersion(reportDocRef
            .getExtensionByUrl(Constants.DocumentReferenceVersionUrl) != null ?
            reportDocRef.getExtensionByUrl(Constants.DocumentReferenceVersionUrl).getValue().toString() : null);
    report.setStatus(reportDocRef.getDocStatus().toString());
    report.setDate(reportDocRef.getDate());

    return report;
  }

  /**
   * Retrieves patient data bundles either from a submission bundle if its report had been sent
   * or from the master measure report if it had not.
   * Can also search for specific patient data bundles by patientId or patientReportId
   *
   * @param masterReportId master report id needed to get the master reportId
   * @param patientId      if searching for a specific patient's data by patientId
   * @return a list of bundles containing data for each patient
   */

  private List<Bundle> getPatientBundles(String masterReportId, String patientId) {
    List<Bundle> patientBundles = new ArrayList<>();

    logger.info("Report not sent: Searching for patient data from master measure report");

    String patientBundleId = masterReportId;
    if (patientId != null && !patientId.isEmpty()) {
      patientBundleId = String.format("%s-%s", masterReportId, ReportIdHelper.hash(patientId));
    }

    Bundle patientBundle = this.getFhirDataProvider().getBundleById(patientBundleId);

    patientBundles.add(patientBundle);

    return patientBundles;
  }

  /**
   * calls getPatientBundles without having to provide a specified patientReportId or patientId
   *
   * @param reportID master report id needed to get the master reportId
   * @return a list of bundles containing data for each patient
   */
  private List<Bundle> getPatientBundles(String reportID) {
    return getPatientBundles(reportID, "");
  }

  private Bundle getPatientResourcesById(String patientId, Bundle ResourceBundle) {
    Bundle patientResourceBundle = new Bundle();
    for (Bundle.BundleEntryComponent entry : ResourceBundle.getEntry()) {
      if (entry.getResource().getId().contains(patientId)) {
        patientResourceBundle.addEntry(entry);
      } else {
        String type = entry.getResource().getResourceType().toString();
        if (type.equals("Condition")) {
          Condition condition = (Condition) entry.getResource();
          if (condition.getSubject().getReference().equals("Patient/" + patientId)) {
            patientResourceBundle.addEntry(entry);
          }
        }
        if (type.equals("MedicationRequest")) {
          MedicationRequest medicationRequest = (MedicationRequest) entry.getResource();
          if (medicationRequest.getSubject().getReference().equals("Patient/" + patientId)) {
            patientResourceBundle.addEntry(entry);
          }
        }
        if (type.equals("Observation")) {
          Observation observation = (Observation) entry.getResource();
          if (observation.getSubject().getReference().equals("Patient/" + patientId)) {
            patientResourceBundle.addEntry(entry);
          }
        }
        if (type.equals("Procedure")) {
          Procedure procedure = (Procedure) entry.getResource();
          if (procedure.getSubject().getReference().equals("Patient/" + patientId)) {
            patientResourceBundle.addEntry(entry);
          }
        }
        if (type.equals("Encounter")) {
          Encounter encounter = (Encounter) entry.getResource();
          if (encounter.getSubject().getReference().equals("Patient/" + patientId)) {
            patientResourceBundle.addEntry(entry);
          }
        }
        if (type.equals("ServiceRequest")) {
          ServiceRequest serviceRequest = (ServiceRequest) entry.getResource();
          if (serviceRequest.getSubject().getReference().equals("Patient/" + patientId)) {
            patientResourceBundle.addEntry(entry);
          }
        }
      }
    }
    return patientResourceBundle;
  }

  private Bundle getPatientBundleByReport(MeasureReport patientReport) {
    Bundle patientBundle = new Bundle();
    List<Reference> refs = patientReport.getEvaluatedResource();
    for (Reference ref : refs) {
      String[] refParts = ref.getReference().split("/");
      if (refParts.length == 2) {
        Resource resource = (Resource) this.getFhirDataProvider().tryGetResource(refParts[0], refParts[1]);
        Bundle.BundleEntryComponent component = new Bundle.BundleEntryComponent().setResource(resource);
        patientBundle.addEntry(component);
      }
    }
    return patientBundle;
  }
}

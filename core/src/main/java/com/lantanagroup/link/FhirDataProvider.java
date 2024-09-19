package com.lantanagroup.link;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.r4.model.MeasureReport;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.IQueryParameterType;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.client.apache.GZipContentInterceptor;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.gclient.DateClientParam;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.param.TokenParam;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.lantanagroup.link.config.api.ApiDataStoreConfig;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;



public class FhirDataProvider {
  private static final Logger logger = LoggerFactory.getLogger(FhirDataProvider.class);
  private static final String PeriodStartParamName = "periodStart";
  private static final String PeriodEndParamName = "periodEnd";
  private static final String ReportDocRefSystem = "urn:ietf:rfc:3986";
  protected FhirContext ctx = FhirContextProvider.getFhirContext();

  @Getter
  private final IGenericClient client;

  public FhirDataProvider(IGenericClient client) {
    this.client = client;
  }

  public FhirDataProvider(ApiDataStoreConfig config) {

    if (StringUtils.isNotEmpty(config.getSocketTimeout())) {
      this.ctx.getRestfulClientFactory().setSocketTimeout(Integer.parseInt(config.getSocketTimeout()));
    }

    if (StringUtils.isNotEmpty(config.getConnectionTimeout())) {
      ctx.getRestfulClientFactory().setConnectTimeout(Integer.parseInt(config.getConnectionTimeout()));
    }

    if (StringUtils.isNotEmpty(config.getConnectionRequestTimeout())) {
      ctx.getRestfulClientFactory().setConnectionRequestTimeout(Integer.parseInt(config.getConnectionRequestTimeout()));
    }

    IGenericClient client = this.ctx.newRestfulGenericClient(config.getBaseUrl());

    if (StringUtils.isNotEmpty(config.getUsername()) && StringUtils.isNotEmpty(config.getPassword())) {
      BasicAuthInterceptor authInterceptor = new BasicAuthInterceptor(config.getUsername(), config.getPassword());
      client.registerInterceptor(authInterceptor);
    }
    this.client = client;
  }

  public FhirDataProvider(String fhirBase) {
    this.client = this.ctx.newRestfulGenericClient(fhirBase);
    this.client.registerInterceptor(new GZipContentInterceptor());
  }

  public Resource createResource(IBaseResource resource) {

    MethodOutcome outcome = this.client
            .create()
            .resource(resource)
            .execute();


    if (!outcome.getCreated() || outcome.getResource() == null) {
      logger.error("Failed to store/create FHIR resource");
    } else {
      logger.debug("Stored FHIR resource with new " + outcome.getId().toString());
    }

    return (Resource) outcome.getResource();
  }

  public MethodOutcome updateResource(IBaseResource resource) {
    int initialVersion = resource.getMeta().getVersionId() != null ? Integer.parseInt(resource.getMeta().getVersionId()) : 0;

    // Make sure the ID is not version-specific
    if (resource.getIdElement() != null && resource.getIdElement().getIdPart() != null) {
      resource.setId(resource.getIdElement().getIdPart());
    }

    MethodOutcome outcome = this.client
            .update()
            .resource(resource)
            .execute();

    Resource domainResource = (Resource) outcome.getResource();
    int updatedVersion = Integer.parseInt(outcome.getId().getVersionIdPart());
    if (updatedVersion > initialVersion) {
      logger.debug(String.format("Update is successful for %s/%s", domainResource.getResourceType().toString(), domainResource.getIdElement().getIdPart()));
    } else {
      logger.info(String.format("Nothing changed in resource %s/%s", domainResource.getResourceType().toString(), domainResource.getIdElement().getIdPart()));
    }

    return outcome;
  }

  public DocumentReference findDocRefByMeasuresAndPeriod(Collection<Identifier> identifiers, String periodStart, String periodEnd) throws Exception {
    DocumentReference documentReference = null;
    DateClientParam periodStartParam = new DateClientParam(PeriodStartParamName);
    DateClientParam periodEndParam = new DateClientParam(PeriodEndParamName);
    List<IQueryParameterType> identifierParams = identifiers.stream()
            .map(identifier -> new TokenParam(identifier.getSystem(), identifier.getValue()))
            .collect(Collectors.toList());

    Bundle bundle = this.client
            .search()
            .forResource(DocumentReference.class)
            .where(Map.of(DocumentReference.SP_IDENTIFIER, identifierParams))
            .and(periodStartParam.exactly().second(periodStart))
            .and(periodEndParam.exactly().second(periodEnd))
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();

    int size = bundle.getEntry().size();
    if (size > 0) {
      if (size == 1) {
        documentReference = (DocumentReference) bundle.getEntry().get(0).getResource();
      } else {
        throw new Exception("We have more than 1 report for the selected measures and report date.");
      }
    }

    return documentReference;
  }

  public DocumentReference findDocRefForReport(String reportId) {
    Bundle bundle = this.client
            .search()
            .forResource(DocumentReference.class)
            .where(DocumentReference.IDENTIFIER.exactly().systemAndValues(ReportDocRefSystem, reportId))
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();

    if (bundle.getEntry().size() != 1) {
      return null;
    }

    return (DocumentReference) bundle.getEntryFirstRep().getResource();
  }

  public Bundle findBundleByIdentifier(String system, String value) {
    Bundle bundle = (Bundle) this.client
            .search()
            .forResource(Bundle.class)
            .where(Bundle.IDENTIFIER.exactly().systemAndValues(system, value))
            .execute();

    if (bundle.getEntry().size() != 1) {
      return null;
    }

    return (Bundle) bundle.getEntryFirstRep().getResource();
  }

  public Bundle findListByIdentifierAndDate(String system, String value, String start, String end) {
    return this.client
            .search()
            .forResource(ListResource.class)
            .where(ListResource.IDENTIFIER.exactly().systemAndValues(system, value))
            .and(new DateClientParam("applicable-period-start").exactly().second(start))
            .and(new DateClientParam("applicable-period-end").exactly().second(end))
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  public Measure getMeasureById(String measureId) {
    return this.client.read().resource(Measure.class).withId(measureId).execute();
  }

  public Task getTaskById(String taskId) {
    return this.client.read().resource(Task.class).withId(taskId).execute();
  }

  public MeasureReport getMeasureReportById(String reportId) {

    return this.client
            .read()
            .resource(MeasureReport.class)
            .withId(reportId)
            .execute();
  }

  public Bundle getBundleById(String bundleId) {

    return this.client
            .read()
            .resource(Bundle.class)
            .withId(bundleId)
            .execute();
  }

  public Bundle getMeasureReportsByIds(List<String> reportIds) {
    // TODO: Is there a practical limit to the number of report IDs we can send here?
    //       E.g., a maximum query string length that HAPI will accept?
    //       If so, modify this logic to use multiple requests
    //       Limit the number of report IDs (based on total query string length?) sent in any single request

    return this.client
            .search()
            .forResource(MeasureReport.class)
            .where(Resource.RES_ID.exactly().codes(reportIds))
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  public Measure getMeasureForReport(DocumentReference docRef) {
    logger.debug(String.format("Getting Measure for DocumentReference %s", docRef.getId()));

    if (docRef.getIdentifier().size() > 0) {
      for (Identifier identifier : docRef.getIdentifier()) {
        Bundle matchingMeasures = this.client
                .search()
                .forResource(Measure.class)
                .where(DocumentReference.IDENTIFIER.exactly().systemAndValues(identifier.getSystem(), identifier.getValue()))
                .returnBundle(Bundle.class)
                .summaryMode(SummaryEnum.FALSE)
                .execute();

        if (matchingMeasures.getEntry().size() == 1) {
          return (Measure) matchingMeasures.getEntry().get(0).getResource();
        }
      }
    } else {
      logger.warn("No identifier specified on DocumentReference");
    }

    return null;
  }

  public Bundle transaction(Bundle txBundle) {
    logger.trace("Executing transaction on " + this.client.getServerBase());

    return this.client
            .transaction()
            .withBundle(txBundle)
            .execute();
  }

  public Measure findMeasureByIdentifier(Identifier identifier) {
    Bundle measureBundle = this.client.search()
            .forResource("Measure")
            .where(Measure.IDENTIFIER.exactly().systemAndIdentifier(identifier.getSystem(), identifier.getValue()))
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();

    if (measureBundle.getEntry().size() != 1) {
      return null;
    }

    return (Measure) measureBundle.getEntryFirstRep().getResource();
  }

  public void audit(HttpServletRequest request, DecodedJWT jwt, FhirHelper.AuditEventTypes type, String outcomeDescription) {
    AuditEvent auditEvent = FhirHelper.createAuditEvent(request, jwt, type, outcomeDescription);
    this.createResource(auditEvent);
  }

  public Bundle getResources(ICriterion<?> criterion, String resourceType) {
    return this.client
            .search()
            .forResource(resourceType)
            .where(criterion)
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  /**
   * Gets a resource by type and ID only including the id property to check if the resource exists
   * @param resourceType The type of resource we are trying to get (List, Patient, etc...)
   * @param resourceId The identifier of the resource we are trying to get
   * @return The gotten Resource
   */
  public IBaseResource tryGetResource(String resourceType, String resourceId) {
    return this.client
            .read()
            .resource(resourceType)
            .withId(resourceId)
            .elementsSubset("id")
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  /**
   * Gets a complete resource by retrieving it based on type and id
   * @param resourceType The type of resource we are trying to get (List, Patient, etc...)
   * @param resourceId The identifier of the resource we are trying to get
   * @return The gotten Resource
   */
  public IBaseResource getResourceByTypeAndId(String resourceType, String resourceId) {
    return this.client
            .read()
            .resource(resourceType)
            .withId(resourceId)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  public MeasureReport getMeasureReport(String measureId, Parameters parameters) {

    //Uncomment to get payload sent to get MeasureReport
    /*
    FhirContext ctx = FhirContext.forR4();
    IParser parser = ctx.newJsonParser();
    String myRequestBody = parser.encodeResourceToString(parameters);
     */

    return client.operation()
            .onInstance(new IdType("Measure", measureId))
            .named("$evaluate-measure")
            .withParameters(parameters)
            .returnResourceType(MeasureReport.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  public Bundle searchPractitioner(String tagSystem, String tagValue) {
    return this.client
            .search()
            .forResource(Practitioner.class)
            .withTag(tagSystem, tagValue)
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  public MethodOutcome createOutcome(IBaseResource resource) {
    return this.client
            .create()
            .resource(resource)
            .prettyPrint()
            .encodedJson()
            .execute();
  }

  public Bundle fetchResourceFromUrl(String url) {
    return this.client.fetchResourceFromUrl(Bundle.class, url);
  }

  public String bundleToXml(Bundle bundle) {
    return client.getFhirContext().newXmlParser().encodeResourceToString(bundle);
  }

  public String bundleToJson(Bundle bundle) {
    return client.getFhirContext().newJsonParser().encodeResourceToString(bundle);
  }

  public Bundle searchReportDefinition(String system, String value) {
    return client.search()
            .forResource(Bundle.class)
            .withTag(Constants.MainSystem, Constants.ReportDefinitionTag)
            .where(Bundle.IDENTIFIER.exactly().systemAndCode(system, value))
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  public Bundle searchPractitioner(String practitionerId) {
    return client
            .search()
            .forResource(Practitioner.class)
            .withTag(Constants.MainSystem, Constants.LinkUserTag)
            .where(Practitioner.IDENTIFIER.exactly().systemAndValues(Constants.MainSystem, practitionerId))
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  public Bundle searchBundleByTag(String system, String value) {
    return client
            .search()
            .forResource(Bundle.class)
            .withTag(system, value)
            .returnBundle(Bundle.class)
            .execute();
  }

  public IBaseResource retrieveFromServer(String resourceType, String resourceId) {
    return client
            .read()
            .resource(resourceType)
            .withId(resourceId)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();
  }

  public Bundle getResourcesSummaryByCountTagLastUpdated(String resourceType, Integer count, String tagSystem, String tagCode, Date lastUpdatedBefore) {

    return client
            .search()
            .forResource(resourceType)
            .count(count)
            .withTag(tagSystem,tagCode)
            .summaryMode(SummaryEnum.TRUE)
            .where(new DateClientParam("_lastUpdated").beforeOrEquals().second(lastUpdatedBefore))
            .returnBundle(Bundle.class)
            .execute();

  }

  public Bundle getResourcesSummaryByCountLastUpdated(String resourceType, Integer count, Date lastUpdatedBefore) {

    return client
            .search()
            .forResource(resourceType)
            .count(count)
            .summaryMode(SummaryEnum.TRUE)
            .where(new DateClientParam("_lastUpdated").beforeOrEquals().second(lastUpdatedBefore))
            .returnBundle(Bundle.class)
            .execute();

  }

  public Bundle getAllResourcesByType(Class<? extends IBaseResource> classType) {
    return client
            .search()
            .forResource(classType)
            .returnBundle(Bundle.class)
            .execute();
  }

  public void deleteResource(String resourceType, String id, boolean permanent) {

    String url = String.format("%s?_id=%s%s", resourceType, id, (permanent?"&_expunge=true":""));

    client.delete().resourceConditionalByUrl(url).execute();

  }
}


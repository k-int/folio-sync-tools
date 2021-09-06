package folio.modrs.scripts

import mod_remote_sync.source.TransformProcess;

import org.springframework.context.ApplicationContext
import groovy.util.logging.Slf4j
import groovy.json.JsonSlurper
import java.time.LocalDateTime
import java.time.LocalDate

import mod_remote_sync.PolicyHelperService
import mod_remote_sync.folio.FolioHelperService
import mod_remote_sync.folio.FolioClient
import mod_remote_sync.folio.FolioClientImpl
import mod_remote_sync.ResourceMappingService
import mod_remote_sync.ResourceMapping
import mod_remote_sync.FeedbackItem
import mod_remote_sync.ImportFeedbackService
import com.k_int.web.toolkit.settings.AppSetting

@Slf4j
public class ProcessLaserSubscription implements TransformProcess {

  // see https://laser-dev.hbz-nrw.de/api/index

  public Map preflightCheck(String resource_id,
                            byte[] input_record,
                            ApplicationContext ctx,
                            Map local_context) {


    boolean pass = false;
    def result = [:]

    try {
      String folio_user = AppSetting.findByKey('laser.ermFOLIOUser')?.value;
      String folio_pass = AppSetting.findByKey('laser.ermFOLIOPass')?.value;
      String okapi_host = System.getenv('OKAPI_SERVICE_HOST') ?: 'okapi';
      String okapi_port = System.getenv('OKAPI_SERVICE_PORT') ?: '9130';

      FolioClient fc = new FolioClientImpl(okapi_host, okapi_port, local_context.tenant, folio_user, folio_pass, 60000);
      fc.ensureLogin();

      local_context.folioClient = fc;

      // test source makes JSON records - so parse the byte array accordingly
      def jsonSlurper = new JsonSlurper()
      def parsed_record = jsonSlurper.parseText(new String(input_record))
      log.info("Process subscription: ${parsed_record}");

      // Stash the parsed record so that we can use it in the process step without re-parsing if preflight passes
      local_context.parsed_record = parsed_record;

      // LAS:eR subcriptions carry the license reference in license.globalUID
      log.info("Try to look up laser license ${local_context.parsed_record?.license?.globalUID}");
      local_context.folio_license_in_force = null;

      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"ProcessLaserSubscription::preflightCheck(${resource_id},..) ${new Date()}"]);

      pass=true
    }
    catch (Exception e) {
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Error in preflight: ${e.message}"]);
    }

    result = [
      preflightStatus: pass ? 'PASS' : 'FAIL'
    ]

    return result;
  }

  public Map process(String resource_id,
                     byte[] input_record,
                     ApplicationContext ctx,
                     Map local_context) {

    Map result = [
      processStatus:'FAIL'   // FAIL|COMPLETE
    ]

    log.info("ProcessLaserSubscription::process(${resource_id})");
    local_context.processLog.add([ts:System.currentTimeMillis(), msg:"ProcessLaserSubscription::process(${resource_id})"]);

    String new_package_name = local_context.parsed_record.name;

    ResourceMappingService rms = ctx.getBean('resourceMappingService');
    ImportFeedbackService feedbackHelper = ctx.getBean('importFeedbackService');

    try {
      // Create or update the "custom package" representing the contents of this agreement
      def folio_package_json = generateFOLIOPackageJSON(new_package_name,local_context.parsed_record);
      // def package_details = upsertPackage(folio_package_json, folioHelper);
      def package_details = upsertPackage(folio_package_json, local_context.folioClient);
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Result of upsert custom package for sub: ${package_details}"]);

      upsertSubscription(local_context.folioClient,
                         '', // prefix
                         local_context.parsed_record,
                         local_context.folio_license_in_force,
                         package_details.id);

    }
    catch ( Exception e ) {
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Exception processing LASER subscription: ${e.message}"]);
      log.error("Exception processing LASER subscription",e);
    }

    return result
  }

  // private Map upsertPackage(Map folio_package_json, FolioHelperService folioHelper) {
  private Map upsertPackage(Map folio_package_json, FolioClient folioHelper) {
    // 1. see if we can locate an existing package with reference folio_package_json.reference (The laser UUID)
    // the /erm/packages/import endpoint automatically checks for an existing record with the given reference and updates
    // any existing package - perfect!
    def import_response = folioHelper.okapiPost('/erm/packages/import', folio_package_json);
    return import_response;
  }

  private Map generateFOLIOPackageJSON(String generated_package_name, Map subscription) {
    def pkg_data = [
      "header": [
         "dataSchema": [
           "name": "mod-agreements-package",
           "version": "1.0"
         ],
         "trustedSourceTI": "true"
       ],
       "records": [
       ]
    ]


    // We build a "custom package" to describe the titles controlled by this subscription - because we want the FOLIO
    // agreement to only contain a package rather than indvidual entitlements (WHich would be much closer to the LAS:eR model)
    def content_items = [];

    // Add a record foe the package
    pkg_data.records.add([
      "source": "LAS:eR",
      "reference": subscription.globalUID,
      "name": generated_package_name,
      // "packageProvider": [
      //   "name": "provider"
      // ],
      "contentItems": content_items
    ])

    // For each package (So far only ever seen one, but hey)
    subscription.packages.each { pkg ->
      // Iterate over the titles in the package and add a content item for each one
      pkg.issueEntitlements.each { ie ->

        ArrayList coverage = buildCoverageStatements(ie.coverages);

        content_items.add([
          //"depth": null,
          "accessStart": dealWithLaserDate(ie.accessStartDate),
          "accessEnd": dealWithLaserDate(ie.accessEndDate),
          "coverage": coverage,
          "platformTitleInstance": [
            "platform": ie.tipp.platform.name,
            "platform_url": ie.tipp.platform.primaryUrl,
            "url": ie.tipp.hostPlatformURL,
            "titleInstance": [
              "name": ie.tipp.title.title,
              "identifiers":ie.tipp.title.identifiers,
              "type": ie.tipp.title.medium,
              "subtype": "electronic",
            ]
          ]
        ])
      }
    }

    // String pkg_json = JsonOutput.toJson(pkg_data);

    log.debug("Package data:${pkg_data}");
    return pkg_data;
  }

  private ArrayList buildCoverageStatements(ArrayList ieCoverages) {
    ArrayList coverageStatements = []
    ieCoverages.each{ ieCoverage ->

      def startDate = dealWithLaserDate(ieCoverage.startDate)
      def endDate = dealWithLaserDate(ieCoverage.endDate)
      
      Map coverageStatement = [
        startDate: startDate,
        endDate: endDate,
        startVolume: ieCoverage.startVolume,
        endVolume: ieCoverage.endVolume,
        startIssue: ieCoverage.startIssue,
        endIssue: ieCoverage.endIssue
      ]
      coverageStatements << coverageStatement
    }

    return coverageStatements;
  }

  private String dealWithLaserDate(String laserDate) {
    String dateOutput = null
    if (laserDate != null) {
      try {
        LocalDateTime laserDateLocalDateTime = LocalDateTime.parse(laserDate)
        LocalDate laserDateLocalDate = laserDateLocalDateTime.toLocalDate()
        dateOutput = laserDateLocalDate.toString()
      } catch (Exception e) {
        println("Warning: failure parsing LAS:eR Date ${laserDate}: ${e.message}")
      }
    }
    return dateOutput;
  }

  // def lookupAgreement(String ref, FolioHelperService folioHelper) {
  def lookupAgreement(String ref, FolioClient folioHelper) {

    log.debug("lookupAgreement(${ref},...)");

    def result = null;

    def search_response = folioHelper.okapiGet('/erm/sas', [
        filters: "localReference==${ref}",
        perPage:10,
        sort:'localReference',
        term:ref,
        stats:true
      ]
    );

    log.debug("lookup agreement response totalRecords: ${search_response?.totalRecords}");

    if ( search_response ) {
      switch ( search_response?.totalRecords ) {
        case 0:
          result = null;
          break;
        case 1:
          result = search_response.results[0]
          break;
        default:
          throw new RuntimeException("Multiple subscriptions matched");
          break;
      }
    }
    else { 
      log.warn("No response to agreement lookup");
    }

    return result;
  }

  def upsertSubscription(FolioClient folioHelper,
                         String prefix, 
                         Map subscription, 
                         String folio_license_id, 
                         String folio_pkg_id = null) {

    def existing_subscription = lookupAgreement(subscription.globalUID, folioHelper)

    if ( existing_subscription ) {
      println("Located existing subscription ${existing_subscription.id} - update");
      updateAgreement(folioHelper, 
                      subscription.name, 
                      subscription, 
                      folio_license_id, 
                      folio_pkg_id, 
                      existing_subscription.id);
    }
    else {
      println("No subscription found - create");
      createAgreement(folioHelper, 
                      subscription, 
                      folio_license_id, 
                      folio_pkg_id);
    }
  }

  def createAgreement(FolioClient folioHelper,
                      Map subscription, 
                      String folio_license_id, 
                      String folio_pkg_id) {
    def result = null;
    println("createAgreement(${subscription.name},${folio_license_id}...)");

    // We only add the custom package as an agreement line if the data from folio contained contentItems
    def items
    if (folio_pkg_id) {
      items = [
        [
          resource: [
            id: folio_pkg_id
          ]
        ]
      ]
    }

    try {
      ArrayList periods = pm.buildPeriodList(subscription)
      // Map statusMappings = pm.getAgreementStatusMap(subscription.status)
      // String statusString = statusMappings.get('agreement.status')
      String statusString = 'Draft';
      String reasonForClosure = null

      if (statusString == null) {
        throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      }
      // reasonForClosure = statusMappings.get('agreement.reasonForClosure')


      result = folioHelper.okapiPost('/erm/sas', {
        [
          name:subscription.name,
          agreementStatus:statusString,
          reasonForClosure: reasonForClosure,
          description:"Imported from LAS:eR on ${new Date()}",
          localReference: subscription.globalUID,
          periods: periods,
          linkedLicenses:[
            [
              remoteId:folio_license_id,
              status:'controlling'
            ]
          ],
          items: items
        ]
      });

      return result;
    } catch (Exception e) {
      println("FATAL ERROR: Skipping agreement creation: ${e.message}")
    }
  }

  def updateAgreement(FolioClient folioHelper,
                      Map subscription, 
                      String folio_license_id, 
                      String folio_pkg_id, 
                      String agreementId) {
    def result = null;
    println("updateAgreement(${subscription.name},${folio_license_id}...)");

    ArrayList linkedLicenses = []

    try {
      Map existing_controlling_license_data = pm.lookupExistingAgreementControllingLicense(subscription.globalUID)
      println("Comparing license id: ${folio_license_id} to existing controlling license link: ${existing_controlling_license_data.existingLicenseId}")
      if (existing_controlling_license_data.existingLicenseId != folio_license_id) {
        println("Existing controlling license differs from data harvested from LAS:eR--updating")
        linkedLicenses = [
          [
            id: existing_controlling_license_data.existingLinkId,
            _delete: true
          ],
          [
            remoteId:folio_license_id,
            status:'controlling'
          ]
        ]
      } else {
        println("Existing controlling license matches data harvested from LAS:eR--moving on")
      }
    } catch (Exception e) {
      println("Warning: Cannot update controlling information for agreement: ${e.message}")
    }

    ArrayList periods = []

    try {
      periods = pm.buildPeriodList(subscription)
      // TODO We don't currently allow for changes in packages to make their way into FOLIO
    } catch (Exception e) {
      println("Warning: Cannot update period information for agreement: ${e.message}")
    }
    String statusString = null
    Map statusMappings = [:]
    try {
      statusMappings = null; // pm.getAgreementStatusMap(subscription.status)
      statusString = null; // statusMappings.get('agreement.status')

      if (statusString == null) {
        throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      }
    } catch (Exception e) {
      println("Warning: Cannot update status information for agreement: ${e.message}")
    }

    Map requestBody = [
      name: subscription.name,
      linkedLicenses: linkedLicenses,
      periods: periods
    ]

    if (statusString != null) {
      requestBody["agreementStatus"] = statusString
      requestBody["reasonForClosure"] = null // statusMappings.get('agreement.reasonForClosure')
    }

    println("Agreement PUT Request body: ${pm.prettyPrinter(requestBody)}")

    result = folioHelper.okapiPut("/erm/sas/${agreementId}", requestBody);

    return result;
  }



}

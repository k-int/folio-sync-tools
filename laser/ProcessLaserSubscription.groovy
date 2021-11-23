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

  private static List<String> REQUIRED_PERMISSIONS = [
    'erm.packages.collection.import'
  ]

  public Map preflightCheck(String resource_id,
                            byte[] input_record,
                            ApplicationContext ctx,
                            Map local_context) {


    boolean pass = false;
    def result = [:]
    ResourceMappingService rms = ctx.getBean('resourceMappingService');

    try {
      String folio_user = AppSetting.findByKey('laser.ermFOLIOUser')?.value;
      String folio_pass = AppSetting.findByKey('laser.ermFOLIOPass')?.value;
      String okapi_host = System.getenv('OKAPI_SERVICE_HOST') ?: 'okapi';
      String okapi_port = System.getenv('OKAPI_SERVICE_PORT') ?: '9130';

      FolioClient fc = new FolioClientImpl(okapi_host, okapi_port, local_context.tenant, folio_user, folio_pass, 60000);
      fc.ensureLogin();

      if ( fc.checkPermissionGranted( REQUIRED_PERMISSIONS ) == false )
        throw new RuntimeException("Configured user is missing a required permission from the set ${REQUIRED_PERMISSIONS}")

      local_context.folioClient = fc;

      // test source makes JSON records - so parse the byte array accordingly
      def jsonSlurper = new JsonSlurper()
      def parsed_record = jsonSlurper.parseText(new String(input_record))
      log.info("Process subscription: ${parsed_record?.name}");

      // Stash the parsed record so that we can use it in the process step without re-parsing if preflight passes
      local_context.parsed_record = parsed_record;

      // LAS:eR subcriptions carry the license reference in license.globalUID
      // We will not try to process this subscription until the license sync task has created a record for the license
      // this sub depends on.
      if ( ( local_context.parsed_record?.licenses != null ) &&
           ( local_context.parsed_record?.licenses?.size() > 0 ) ) {
        String laser_license_guid = local_context.parsed_record?.licenses[0]?.globalUID;
        log.info("Try to look up laser license ${laser_license_guid}");
        if ( laser_license_guid != null ) {
          ResourceMapping license_rm = rms.lookupMapping('LASER-LICENSE', laser_license_guid, 'LASERIMPORT')
          if ( license_rm != null ) {
            local_context.folio_license_in_force = license_rm.folioId;
            log.debug("Located local laser license ${license_rm.folioId} for LASER license ${laser_license_guid}");
          }
          else {
            local_context.processLog.add([ts:System.currentTimeMillis(), msg:"No FOLIO license for LASER:${laser_license_guid}"]);
            log.warn("Unable to find local laser license for LASER license ${laser_license_guid}");
          }
        }
        else {
          local_context.processLog.add([ts:System.currentTimeMillis(), msg:"No LASER License referenced in sub - unable to continue"]);
        }
      }
      else {
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Source subscription does not seem to carry license data"]);
      }

      // We're passing everything - but not mapping licenses we don't know about
      pass=true
      local_context.processLog.add([ts:System.currentTimeMillis(), msg:"ProcessLaserSubscription::preflightCheck(${resource_id},..) ${new Date()}"]);
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

      def package_details = null;
      if ( folio_package_json.records[0].contentItems?.size() > 0 ) {
        package_details = upsertPackage(folio_package_json, local_context.folioClient);
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Result of upsert custom package for sub: ${package_details}"]);
      }
      else {
        local_context.processLog.add([ts:System.currentTimeMillis(), msg:"Found no items for package - skip package creation"]);
      }

      def upsert_sub_result = upsertSubscription(local_context.folioClient,
                         '', // prefix
                         local_context.parsed_record,
                         local_context.folio_license_in_force,
                         result,
                         package_details?.packageId);

      if ( upsert_sub_result?.id != null ) {
        result.processStatus = 'COMPLETE'
        // result.resource_mapping = 
      }
      else {
        log.warn("Result of upsertSubscription: ${upsert_sub_result}");
      }

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
    log.debug("/erm/packages/import response: ${import_response}");
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

    // log.debug("Package data:${pkg_data}");
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
                         Map processing_result,
                         String folio_pkg_id = null) {

    log.debug("upsertSubscription(...,${prefix},${folio_license_id},${folio_pkg_id}...");

    def existing_subscription = lookupAgreement(subscription.globalUID, folioHelper)
    def result = null;

    if ( existing_subscription ) {
      println("Located existing subscription ${existing_subscription.id} - update - ${folio_pkg_id}");
      result = updateAgreement(folioHelper, 
                      subscription, 
                      folio_license_id, 
                      folio_pkg_id, 
                      existing_subscription);
    }
    else {
      println("No subscription found - create - package will be ${folio_pkg_id}");
      result = createAgreement(folioHelper, 
                      subscription, 
                      folio_license_id, 
                      folio_pkg_id);
      if ( result && result.id != null ) {
        log.debug("We should stash the mapped agreement details here");
      }
    }

    return result;
  }

  def createAgreement(FolioClient folioHelper,
                      Map subscription, 
                      String folio_license_id, 
                      String folio_pkg_id) {
    def result = null;
    println("createAgreement(${subscription.name},${folio_license_id},${folio_pkg_id}...)");

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
    else {
      log.debug("No folio package found corresponding to laser sub.. Skip add content")
    }

    try {
      ArrayList periods = buildPeriodList(subscription, null)
      // Map statusMappings = pm.getAgreementStatusMap(subscription.status)
      // String statusString = statusMappings.get('agreement.status')
      String statusString = 'Draft';
      String reasonForClosure = null

      if (statusString == null) {
        // throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      }
      // reasonForClosure = statusMappings.get('agreement.reasonForClosure')

      def linked_licenses = null;
      if ( folio_license_id ) {
        linked_licenses = [
          [
            remoteId:folio_license_id,
            status:'controlling'
          ]
        ]
      }


      result = folioHelper.okapiPost('/erm/sas',
        [
          name:subscription.name,
          agreementStatus:statusString,
          reasonForClosure: reasonForClosure,
          description:"Imported from LAS:eR on ${new Date()}",
          localReference: subscription.globalUID,
          periods: periods,
          linkedLicenses: linked_licenses,
          items: items
        ]
      );

    } catch (Exception e) {
      println("FATAL ERROR: Skipping agreement creation: ${e.message}")
    }

    return result;
  }

  def updateAgreement(FolioClient folioHelper,
                      Map subscription, 
                      String folio_license_id, 
                      String folio_pkg_id, 
                      Map folio_agreement) {

    String agreementId = folio_agreement.id
    def result = null;
    println("updateAgreement(name:${subscription.name},folio_license_id:${folio_license_id}...)");

    ArrayList linkedLicenses = []

    try {
      Map existing_controlling_license_data = lookupExistingAgreementControllingLicense(folio_agreement)
      println("Comparing license id: ${folio_license_id} to existing controlling license link: ${existing_controlling_license_data.existingLicenseId}")
      if ( ( existing_controlling_license_data == null ) ||
           (existing_controlling_license_data.existingLicenseId != folio_license_id) ) {
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
      periods = buildPeriodList(subscription, folio_agreement)
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
        throw new Exception ("Mapping not found for LAS:eR status ${folio_agreement.status}")
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

    result = folioHelper.okapiPut("/erm/sas/${agreementId}", requestBody);

    return result;
  }


  public ArrayList buildPeriodList(Map subscription, Map folioAgreement) {
    println("buildPeriodList ::${subscription.globalUID}")

    if (subscription.startDate == null) {
      throw new RuntimeException ("There is no startDate for this subscription")
    }

    ArrayList periodList = []

    if (folioAgreement) {
      // We already have an agreement, so the period will need updating
      if ( ( folioAgreement.periods ) && 
           ( folioAgreement.periods?.size() > 0 ) ) {
        Map deleteMap = [
          id: folioAgreement.periods?.getAt(0)?.id,
          _delete: true
        ]
        periodList.add(deleteMap)
      }
    }

    Map newPeriod = [
      startDate: subscription.startDate,
      endDate: subscription.endDate
    ]

    periodList.add(newPeriod)

    return periodList;
  }

  // Returns the license link id of the current controlling license
  public Map lookupExistingAgreementControllingLicense(Map folio_agreement) {

    Map result = [:]

    ArrayList linkedLicenses = [] // folio_agreement.linkedLicenses
    linkedLicenses = folio_agreement?.linkedLicenses?.find { obj.status?.value == 'controlling' };
    if ( linkedLicenses == null ) 
      linkedLicenses=[];

    // The below should always go smoothly, since FOLIO only allows a single controlling license. If this fails then something hasd gone wrong internally in FOLIO
    switch ( linkedLicenses.size() ) {
      case 0:
        result = [existingLinkId: null, existingLicenseId: null];
        break;
      case 1:
        result = [existingLinkId: linkedLicenses[0].id, existingLicenseId: linkedLicenses[0].remoteId]
        break;
      default:
        throw new RuntimeException("Multiple agreement controlling licenses found/2 (${linkedLicenses.size()})");
        break;
    }
    return result;
  }

}

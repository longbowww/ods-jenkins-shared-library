package org.ods.orchestration.util

import com.cloudbees.groovy.cps.NonCPS

import groovy.json.JsonOutput
import org.ods.orchestration.service.leva.ProjectDataBitbucketRepository

import java.nio.file.Paths

import org.apache.http.client.utils.URIBuilder
import org.ods.orchestration.usecase.*
import org.yaml.snakeyaml.Yaml
import org.ods.services.GitService
import org.ods.services.NexusService
import org.ods.util.IPipelineSteps
import org.ods.util.ILogger

@SuppressWarnings(['LineLength',
    'AbcMetric',
    'IfStatementBraces',
    'Instanceof',
    'CyclomaticComplexity',
    'GStringAsMapKey',
    'ImplementationAsType',
    'UseCollectMany',
    'MethodCount',
    'PublicMethodsBeforeNonPublicMethods'])
class Project {

    class JiraDataItem implements Map, Serializable {
        static final String TYPE_BUGS = 'bugs'
        static final String TYPE_COMPONENTS = 'components'
        static final String TYPE_EPICS = 'epics'
        static final String TYPE_MITIGATIONS = 'mitigations'
        static final String TYPE_REQUIREMENTS = 'requirements'
        static final String TYPE_RISKS = 'risks'
        static final String TYPE_TECHSPECS = 'techSpecs'
        static final String TYPE_TESTS = 'tests'
        static final String TYPE_DOCS = 'docs'

        static final List TYPES = [
            TYPE_BUGS,
            TYPE_COMPONENTS,
            TYPE_EPICS,
            TYPE_MITIGATIONS,
            TYPE_REQUIREMENTS,
            TYPE_RISKS,
            TYPE_TECHSPECS,
            TYPE_TESTS,
        ]

        static final List TYPES_WITH_STATUS = [
            TYPE_BUGS,
            TYPE_EPICS,
            TYPE_MITIGATIONS,
            TYPE_REQUIREMENTS,
            TYPE_RISKS,
            TYPE_TECHSPECS,
            TYPE_TESTS,
        ]

        static final String ISSUE_STATUS_DONE = 'done'
        static final String ISSUE_STATUS_CANCELLED = 'cancelled'

        static final String ISSUE_TEST_EXECUTION_TYPE_AUTOMATED = 'automated'

        private final String type
        private HashMap delegate

        JiraDataItem(Map map, String type) {
            this.delegate = new HashMap(map)
            this.type = type
        }

        @NonCPS
        @Override
        int size() {
            return delegate.size()
        }

        @NonCPS
        @Override
        boolean isEmpty() {
            return delegate.isEmpty()
        }

        @NonCPS
        @Override
        boolean containsKey(Object key) {
            return delegate.containsKey(key)
        }

        @NonCPS
        @Override
        boolean containsValue(Object value) {
            return delegate.containsValue(value)
        }

        @NonCPS
        @Override
        Object get(Object key) {
            return delegate.get(key)
        }

        @NonCPS
        @Override
        Object put(Object key, Object value) {
            return delegate.put(key, value)
        }

        @NonCPS
        @Override
        Object remove(Object key) {
            return delegate.remove(key)
        }

        @NonCPS
        @Override
        void putAll(Map m) {
            delegate.putAll(m)
        }

        @NonCPS
        @Override
        void clear() {
            delegate.clear()
        }

        @NonCPS
        @Override
        Set keySet() {
            return delegate.keySet()
        }

        @NonCPS
        @Override
        Collection values() {
            return delegate.values()
        }

        @NonCPS
        @Override
        Set<Entry> entrySet() {
            return delegate.entrySet()
        }

        @NonCPS
        String getType() {
            return type
        }

        @NonCPS
        Map getDelegate() {
            return delegate
        }

        @NonCPS
        JiraDataItem cloneIt() {
            def bos = new ByteArrayOutputStream()
            def os = new ObjectOutputStream(bos)
            os.writeObject(this.delegate)
            def ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))

            def newDelegate = ois.readObject()
            JiraDataItem result = new JiraDataItem(newDelegate, type)
            return result
        }

        @NonCPS
        // FIXME: why can we not invoke derived methods in short form, e.g. .resolvedBugs?
        private List<JiraDataItem> getResolvedReferences(String type) {
            // Reference this within jiraResolved (contains readily resolved references to other entities)
            def item = Project.this.data.jiraResolved[this.type][this.key]
            return item[type] ?: []
        }

        List<JiraDataItem> getResolvedBugs() {
            return this.getResolvedReferences(TYPE_BUGS)
        }

        List<JiraDataItem> getResolvedComponents() {
            return this.getResolvedReferences(TYPE_COMPONENTS)
        }

        List<JiraDataItem> getResolvedEpics() {
            return this.getResolvedReferences(TYPE_EPICS)
        }

        List<JiraDataItem> getResolvedMitigations() {
            return this.getResolvedReferences(TYPE_MITIGATIONS)
        }

        List<JiraDataItem> getResolvedSystemRequirements() {
            return this.getResolvedReferences(TYPE_REQUIREMENTS)
        }

        List<JiraDataItem> getResolvedRisks() {
            return this.getResolvedReferences(TYPE_RISKS)
        }

        List<JiraDataItem> getResolvedTechnicalSpecifications() {
            return this.getResolvedReferences(TYPE_TECHSPECS)
        }

        List<JiraDataItem> getResolvedTests() {
            return this.getResolvedReferences(TYPE_TESTS)
        }

    }

    class TestType {

        static final String ACCEPTANCE = 'Acceptance'
        static final String INSTALLATION = 'Installation'
        static final String INTEGRATION = 'Integration'
        static final String UNIT = 'Unit'

    }

    class GampTopic {

        static final String AVAILABILITY_REQUIREMENT = 'Availability Requirement'
        static final String CONSTRAINT = 'Constraint'
        static final String FUNCTIONAL_REQUIREMENT = 'Functional Requirement'
        static final String INTERFACE_REQUIREMENT = 'Interface Requirement'

    }

    protected static final String BUILD_PARAM_VERSION_DEFAULT = 'WIP'

    protected static String METADATA_FILE_NAME = 'metadata.yml'

    protected IPipelineSteps steps
    protected GitService git
    protected JiraUseCase jiraUseCase
    protected ILogger logger
    protected Map config

    protected Map data = [:]

    Project(IPipelineSteps steps, ILogger logger, Map config = [:]) {
        this.steps = steps
        this.config = config
        this.logger = logger

        this.data.build = [
            hasFailingTests: false,
            hasUnexecutedJiraTests: false,
        ]
    }

    Project init() {
        this.data.buildParams = this.loadBuildParams(steps)
        this.data.metadata = this.loadMetadata(METADATA_FILE_NAME)
        return this
    }

    // CAUTION! This needs to be called from the root of the release manager repo.
    // Otherwise the Git information cannot be retrieved correctly.
    Project initGitDataAndJiraUsecase(GitService git, JiraUseCase usecase) {
        if (usecase) {
            // add to notify jira back, even in this super early case
            this.jiraUseCase = usecase
        }
        def version = this.data.buildParams.version
        def changeId = this.data.buildParams.changeId
        def targetEnvironmentToken = this.data.buildParams.targetEnvironmentToken
        def baseTag = null
        def targetTag = null
        if (!getIsWorkInProgress()) {
            def tagList = git.readBaseTagList(version, changeId, targetEnvironmentToken)
            baseTag = GitTag.readLatestBaseTag(tagList, version, changeId, targetEnvironmentToken)

            if (getIsAssembleMode()) {
                if (baseTag) {
                    targetTag = baseTag.nextIterationWithBuildNumber(steps.env.BUILD_NUMBER)
                } else {
                    targetTag = new GitTag(version, changeId, 0, steps.env.BUILD_NUMBER, targetEnvironmentToken)
                }
            } else {
                if (baseTag) {
                    targetTag = baseTag.withEnvToken(targetEnvironmentToken)
                } else {
                    throw new RuntimeException("Error: unable to find latest tag for ${version}/${changeId}.")
                }
            }
        }

        this.data.git = [
            commit: git.getCommitSha(),
            url: git.getOriginUrl(),
            baseTag: baseTag ? baseTag.toString() : '',
            targetTag: targetTag ? targetTag.toString() : '',
            author: git.getCommitAuthor(),
            message: git.getCommitMessage(),
            time: git.getCommitTime(),
        ]
        this.logger.debug "Using release manager commit: ${this.data.git.commit}"
    }

    Project load(GitService git, JiraUseCase jiraUseCase) {
        this.git = git
        this.jiraUseCase = jiraUseCase

        this.data.jira = [:]
        this.data.jira.issueTypes = this.loadJiraDataIssueTypes()

        def newData = this.loadJiraData(this.jiraProjectKey)
        // TODO removeme when jrra plugin is updated
        //newData.version = newData.project.version
        //newData.predecessors = []
        //newData.predecessors = ['1.0']

        // Get more info of the versions from Jira
        def predecessors = newData.getOrDefault("predecessors", [])
        def previousVersionId = null
        if (! predecessors.isEmpty()) {
            previousVersionId = predecessors.first()
        }

        if (previousVersionId) {
            def savedDataFromOldVersion = this.loadSavedJiraData(previousVersionId)
            def mergedData = this.mergeJiraData(savedDataFromOldVersion, newData)
            this.data.jira << this.addVersionToComponentsWithout(mergedData)
            this.data.jira.previousVersion = previousVersionId
        } else {
            this.data.jira << this.addVersionToComponentsWithout(newData)
        }

        // Get more info of the versions from Jira
        this.data.jira.project.version = this.loadCurrentVersionDataFromJira()
        this.data.jira.project.previousVersion = this.loadVersionDataFromJira(previousVersionId)

        this.data.jira.bugs = this.loadJiraDataBugs(this.data.jira.tests) // TODO removeme when endpoint is updated
        this.data.jira = this.convertJiraDataToJiraDataItems(this.data.jira)

        this.data.jiraResolved = this.resolveJiraDataItemReferences(this.data.jira)

        this.data.jira.docs = this.loadJiraDataDocs()

        this.data.jira.undone = this.computeWipJiraIssues(this.data.jira)
        this.data.jira.undone.docChapters = [:]

        if (this.hasWipJiraIssues()) {
            def message = 'Pipeline-generated documents are watermarked ' +
                "'${LeVADocumentUseCase.WORK_IN_PROGRESS_WATERMARK}' " +
                'since the following issues are work in progress: '
            this.getWipJiraIssues().each { type, keys ->
                def values = keys instanceof Map ? keys.values().flatten() : keys
                if (!values.isEmpty()) {
                    message += '\n\n' + type.capitalize() + ': ' + values.join(', ')
                }
            }

            this.addCommentInReleaseStatus(message)
        }

        this.data.documents = [:]
        this.data.openshift = [:]

        this.jiraUseCase.updateJiraReleaseStatusBuildNumber()

        return this
    }

    Map<String, List> getWipJiraIssues() {
        return this.data.jira.undone
    }

    boolean hasWipJiraIssues() {
        def values = this.getWipJiraIssues().values()
        values = values.collect { it instanceof Map ? it.values() : it }.flatten()
        return !values.isEmpty()
    }

    protected Map<String, List> computeWipJiraIssues(Map data) {
        def result = [:]

        JiraDataItem.TYPES_WITH_STATUS.each { type ->
            if (data.containsKey(type)) {
                result[type] = data[type]
                    .findAll { key, issue ->
                        issue.status != null &&
                        !issue.status.equalsIgnoreCase(JiraDataItem.ISSUE_STATUS_DONE) &&
                        !issue.status.equalsIgnoreCase(JiraDataItem.ISSUE_STATUS_CANCELLED)
                    }
                    .collect { key, issue ->
                        return key
                    }
            }
        }

        return result
    }

    protected Map convertJiraDataToJiraDataItems(Map data) {
        JiraDataItem.TYPES.each { type ->
            if (data[type] == null) {
                throw new IllegalArgumentException(
                    "Error: Jira data does not include references to items of type '${type}'.")
            }

            data[type] = data[type].collectEntries { key, item ->
                return [key, new JiraDataItem(item, type)]
            }
        }

        return data
    }

    List<JiraDataItem> getAutomatedTests(String componentName = null, List<String> testTypes = []) {
        return this.data.jira.tests.findAll { key, testIssue ->
            def result = testIssue.status.toLowerCase() == JiraDataItem.ISSUE_STATUS_DONE &&
                testIssue.executionType?.toLowerCase() == JiraDataItem.ISSUE_TEST_EXECUTION_TYPE_AUTOMATED

            if (result && componentName) {
                result = testIssue.getResolvedComponents()
                    .collect { it.name.toLowerCase() }
                    .contains(componentName.toLowerCase())
            }

            if (result && testTypes) {
                result = testTypes.collect { it.toLowerCase() }.contains(testIssue.testType.toLowerCase())
            }

            return result
        }.values() as List
    }

    Map getEnumDictionary(String name) {
        return this.data.jira.project.enumDictionary[name]
    }

    Map getProjectProperties() {
        return this.data.jira.project.projectProperties
    }

    List<JiraDataItem> getAutomatedTestsTypeAcceptance(String componentName = null) {
        return this.getAutomatedTests(componentName, [TestType.ACCEPTANCE])
    }

    List<JiraDataItem> getAutomatedTestsTypeInstallation(String componentName = null) {
        return this.getAutomatedTests(componentName, [TestType.INSTALLATION])
    }

    List<JiraDataItem> getAutomatedTestsTypeIntegration(String componentName = null) {
        return this.getAutomatedTests(componentName, [TestType.INTEGRATION])
    }

    List<JiraDataItem> getAutomatedTestsTypeUnit(String componentName = null) {
        return this.getAutomatedTests(componentName, [TestType.UNIT])
    }

    boolean getIsPromotionMode() {
        isPromotionMode(buildParams.targetEnvironmentToken)
    }

    boolean getIsAssembleMode() {
        !getIsPromotionMode()
    }

    static boolean isPromotionMode(String targetEnvironmentToken) {
        ['Q', 'P'].contains(targetEnvironmentToken)
    }

    boolean getIsWorkInProgress() {
        isWorkInProgress(buildParams.version)
    }

    boolean isDeveloperPreviewMode() {
        return BUILD_PARAM_VERSION_DEFAULT.equalsIgnoreCase(this.data.buildParams.version) &&
            this.data.buildParams.targetEnvironmentToken == "D"
    }

    static boolean isWorkInProgress(String version) {
        version == BUILD_PARAM_VERSION_DEFAULT
    }

    static String envStateFileName(String targetEnvironment) {
        "${MROPipelineUtil.ODS_STATE_DIR}/${targetEnvironment}.json"
    }

    String getEnvStateFileName() {
        envStateFileName(buildParams.targetEnvironment)
    }

    Map getBuildParams() {
        return this.data.buildParams
    }

    String getEnvironmentParamsFile() {
        def envParamsFile = "${steps.env.WORKSPACE}/${buildParams.targetEnvironment}.env"
        if (!steps.fileExists(envParamsFile)) {
            envParamsFile = ''
        }
        envParamsFile
    }

    Map getEnvironmentParams(String envParamsFile) {
        def envParams = [:]
        if (envParamsFile) {
            def paramsFileContent = steps.readFile(file: envParamsFile)
            def params = paramsFileContent.split('\n')
            envParams = params.collectEntries {
                if (it.trim().size() > 0 && !it.trim().startsWith('#')) {
                    def vals = it.split('=')
                    [vals.first().trim(), vals[1..vals.size()-1].join('=').trim()]
                } else {
                    [:]
                }
            }
        }
        envParams
    }

    void setOpenShiftData(String sessionApiUrl) {
        def envConfig = getEnvironmentConfig()
        def targetApiUrl = envConfig?.apiUrl
        if (!targetApiUrl) {
            targetApiUrl = sessionApiUrl
        }
        this.data.openshift['sessionApiUrl'] = sessionApiUrl
        this.data.openshift['targetApiUrl'] = targetApiUrl
    }

    @NonCPS
    boolean getTargetClusterIsExternal() {
        def isExternal = false
        def sessionApiUrl = this.data.openshift.sessionApiUrl
        def targetApiUrl = this.data.openshift.targetApiUrl
        def targetApiUrlMatcher = targetApiUrl =~ /:[0-9]+$/
        if (targetApiUrlMatcher.find()) {
            isExternal = sessionApiUrl != targetApiUrl
        } else {
            def sessionApiUrlWithoutPort = sessionApiUrl.split(':').dropRight(1).join(':')
            isExternal = sessionApiUrlWithoutPort != targetApiUrl
        }
        isExternal
    }

    String getSourceEnv() {
        ['dev': 'dev', 'qa': 'dev', 'prod': 'qa'].get(buildParams.targetEnvironment)
    }

    String getBaseTag() {
        this.data.git.baseTag
    }

    def getTargetTag() {
        this.data.git.targetTag
    }

    boolean getVersionedDevEnvsEnabled() {
        this.config.get('versionedDevEnvs', false)
    }

    String getConcreteEnvironment() {
        def versionedDevEnvs = getVersionedDevEnvsEnabled()
        getConcreteEnvironment(buildParams.targetEnvironment, buildParams.version, versionedDevEnvs)
    }

    static String getConcreteEnvironment(String environment, String version, boolean versionedDevEnvsEnabled) {
        if (versionedDevEnvsEnabled && environment == 'dev' && version != BUILD_PARAM_VERSION_DEFAULT) {
            def cleanedVersion = version.replaceAll('[^A-Za-z0-9-]', '-').toLowerCase()
            environment = "${environment}-${cleanedVersion}"
        } else if (environment == 'qa') {
            environment = 'test'
        }
        environment
    }

    static List<String> getBuildEnvironment(IPipelineSteps steps, boolean debug = false, boolean versionedDevEnvsEnabled = false) {
        def params = loadBuildParams(steps)

        def concreteEnv = getConcreteEnvironment(params.targetEnvironment, params.version, versionedDevEnvsEnabled)

        return [
            "DEBUG=${debug}",
            'MULTI_REPO_BUILD=true',
            "MULTI_REPO_ENV=${concreteEnv}",
            "MULTI_REPO_ENV_TOKEN=${params.targetEnvironmentToken}",
            "RELEASE_PARAM_CHANGE_ID=${params.changeId}",
            "RELEASE_PARAM_CHANGE_DESC=${params.changeDescription}",
            "RELEASE_PARAM_CONFIG_ITEM=${params.configItem}",
            "RELEASE_PARAM_VERSION=${params.version}",
            "RELEASE_STATUS_JIRA_ISSUE_KEY=${params.releaseStatusJiraIssueKey}",
        ]
    }

    List getCapabilities() {
        return this.data.metadata.capabilities
    }

    Object getCapability(String name) {
        def entry = this.getCapabilities().find { it instanceof Map ? it.find { it.key == name } : it == name }
        if (entry) {
            return entry instanceof Map ? entry[name] : true
        }

        return null
    }

    List<JiraDataItem> getBugs() {
        return this.data.jira.bugs.values() as List
    }

    List<JiraDataItem> getComponents() {
        return this.data.jira.components.values() as List
    }

    String getDescription() {
        return this.data.metadata.description
    }

    List<Map> getDocumentTrackingIssues() {
        return this.data.jira.docs.values() as List
    }

    List<Map> getDocumentTrackingIssues(List<String> labels) {
        def result = []

        def issues = this.getDocumentTrackingIssues()
        labels.each { label ->
            issues.each { issue ->
                if (issue.labels.collect { it.toLowerCase() }.contains(label.toLowerCase())) {
                    result << [key: issue.key, status: issue.status]
                }
            }
        }

        return result.unique()
    }

    List<Map> getDocumentTrackingIssuesNotDone(List<String> labels) {
        return this.getDocumentTrackingIssues(labels).findAll {
            !it.status.equalsIgnoreCase(JiraDataItem.ISSUE_STATUS_DONE)
        }
    }

    Map getGitData() {
        return this.data.git
    }

    protected URI getGitURLFromPath(String path, String remote = 'origin') {
        if (!path?.trim()) {
            throw new IllegalArgumentException("Error: unable to get Git URL. 'path' is undefined.")
        }

        if (!path.startsWith(this.steps.env.WORKSPACE)) {
            throw new IllegalArgumentException("Error: unable to get Git URL. 'path' must be inside the Jenkins workspace: ${path}")
        }

        if (!remote?.trim()) {
            throw new IllegalArgumentException("Error: unable to get Git URL. 'remote' is undefined.")
        }

        def result = null

        this.steps.dir(path) {
            result = this.steps.sh(
                label: "Get Git URL for repository at path '${path}' and origin '${remote}'",
                script: "git config --get remote.${remote}.url",
                returnStdout: true
            ).trim()
        }

        return new URIBuilder(result).build()
    }

    List<JiraDataItem> getEpics() {
        return this.data.jira.epics.values() as List
    }

    String getId() {
        return this.data.jira.project.id
    }

    Map getVersion() {
        return this.data.jira.project.version
    }

    String getVersionName(){
        return this.data.jira.version
    }

    String getPreviousVersionId() {
        return this.data.jira.project.previousVersion?.id
    }

    Map getJiraFieldsForIssueType(String issueTypeName) {
        return this.data.jira.issueTypes[issueTypeName]?.fields ?: [:]
    }

    String getKey() {
        return this.data.metadata.id
    }

    String getJiraProjectKey() {
        def services = this.getServices()
        if (services?.jira?.project) {
            return services.jira.project
        }

        return getKey()
    }

    List<JiraDataItem> getMitigations() {
        return this.data.jira.mitigations.values() as List
    }

    String getName() {
        return this.data.metadata.name
    }

    List<Map> getRepositories() {
        return this.data.metadata.repositories
    }

    Map getEnvironments() {
        return this.data.metadata.environments
    }

    List<JiraDataItem> getRisks() {
        return this.data.jira.risks.values() as List
    }

    Map getServices() {
        return this.data.metadata.services
    }

    List<JiraDataItem> getSystemRequirements(String componentName = null, List<String> gampTopics = []) {
        return this.data.jira.requirements.findAll { key, req ->
            def result = true

            if (result && componentName) {
                result = req.getResolvedComponents().collect { it.name.toLowerCase() }.
                    contains(componentName.toLowerCase())
            }

            if (result && gampTopics) {
                result = gampTopics.collect { it.toLowerCase() }.contains(req.gampTopic.toLowerCase())
            }

            return result
        }.values() as List
    }

    List<JiraDataItem> getSystemRequirementsTypeAvailability(String componentName = null) {
        return this.getSystemRequirements(componentName, [GampTopic.AVAILABILITY_REQUIREMENT])
    }

    List<JiraDataItem> getSystemRequirementsTypeConstraints(String componentName = null) {
        return this.getSystemRequirements(componentName, [GampTopic.CONSTRAINT])
    }

    List<JiraDataItem> getSystemRequirementsTypeFunctional(String componentName = null) {
        return this.getSystemRequirements(componentName, [GampTopic.FUNCTIONAL_REQUIREMENT])
    }

    List<JiraDataItem> getSystemRequirementsTypeInterfaces(String componentName = null) {
        return this.getSystemRequirements(componentName, [GampTopic.INTERFACE_REQUIREMENT])
    }

    List<JiraDataItem> getTechnicalSpecifications(String componentName = null) {
        return this.data.jira.techSpecs.findAll { key, techSpec ->
            def result = true

            if (result && componentName) {
                result = techSpec.getResolvedComponents().collect { it.name.toLowerCase() }.
                    contains(componentName.toLowerCase())
            }

            return result
        }.values() as List
    }

    List<JiraDataItem> getTests() {
        return this.data.jira.tests.values() as List
    }

    Map getEnvironmentConfig() {
        def environments = getEnvironments()
        environments[buildParams.targetEnvironment]
    }

    // Deprecated in favour of getOpenShiftTargetApiUrl
    String getOpenShiftApiUrl() {
        this.data.openshift.targetApiUrl
    }

    String getOpenShiftTargetApiUrl() {
        this.data.openshift.targetApiUrl
    }

    boolean hasCapability(String name) {
        def collector = {
            return (it instanceof Map) ? it.keySet().first().toLowerCase() : it.toLowerCase()
        }

        return this.capabilities.collect(collector).contains(name.toLowerCase())
    }

    boolean hasFailingTests() {
        return this.data.build.hasFailingTests
    }

    boolean hasUnexecutedJiraTests() {
        return this.data.build.hasUnexecutedJiraTests
    }

    static boolean isTriggeredByChangeManagementProcess(steps) {
        def changeId = steps.env.changeId?.trim()
        def configItem = steps.env.configItem?.trim()
        return changeId && configItem
    }

    String getGitReleaseBranch() {
        GitService.getReleaseBranch(buildParams.version)
    }

    String getTargetProject() {
        "${getKey()}-${getConcreteEnvironment()}"
    }

    static Map loadBuildParams(IPipelineSteps steps) {
        def releaseStatusJiraIssueKey = steps.env.releaseStatusJiraIssueKey?.trim()
        if (isTriggeredByChangeManagementProcess(steps) && !releaseStatusJiraIssueKey) {
            throw new IllegalArgumentException(
                "Error: unable to load build param 'releaseStatusJiraIssueKey': undefined")
        }

        def version = steps.env.version?.trim() ?: BUILD_PARAM_VERSION_DEFAULT
        def targetEnvironment = (steps.env.environment?.trim() ?: 'dev').toLowerCase()
        if (!['dev', 'qa', 'prod'].contains(targetEnvironment)) {
            throw new IllegalArgumentException("Error: 'environment' build param must be one of 'DEV', 'QA' or 'PROD'.")
        }
        def targetEnvironmentToken = targetEnvironment[0].toUpperCase()

        def changeId = steps.env.changeId?.trim() ?: 'UNDEFINED'
        def configItem = steps.env.configItem?.trim() ?: 'UNDEFINED'
        def changeDescription = steps.env.changeDescription?.trim() ?: 'UNDEFINED'
        // Set rePromote=true if an existing tag should be deployed again
        def rePromote = steps.env.rePromote?.trim() == 'true'

        return [
            changeDescription: changeDescription,
            changeId: changeId,
            configItem: configItem,
            releaseStatusJiraIssueKey: releaseStatusJiraIssueKey,
            targetEnvironment: targetEnvironment,
            targetEnvironmentToken: targetEnvironmentToken,
            version: version,
            rePromote: rePromote,
        ]
    }

    protected Map loadJiraData(String projectKey) {
        def result = [
            components: [:],
            epics: [:],
            mitigations: [:],
            project: [:],
            requirements: [:],
            risks: [:],
            techSpecs: [:],
            tests: [:],
        ]

        if (!this.jiraUseCase) return result
        if (!this.jiraUseCase.jira) return result

        result = this.jiraUseCase.jira.getDocGenData(projectKey)
        if (result?.project?.id == null) {
            throw new IllegalArgumentException(
                "Error: unable to load documentation generation data from Jira. 'project.id' is undefined.")
        }

        // FIXME: fix data types that should be sent correctly by the REST endpoint
        result.project.id = result.project.id as String

        result.tests.each { key, test ->
            test.id = test.id as String
            test.bugs = test.bugs ?: []
        }

        return result
    }

    protected Map loadJiraDataBugs(Map tests) {
        if (!this.jiraUseCase) return [:]
        if (!this.jiraUseCase.jira) return [:]

        def jqlQuery = [
            jql: "project = ${this.jiraProjectKey} AND issuetype = Bug AND status != Done",
            expand: [],
            fields: ['assignee', 'duedate', 'issuelinks', 'status', 'summary']
        ]

        def jiraBugs = this.jiraUseCase.jira.getIssuesForJQLQuery(jqlQuery) ?: []
        return jiraBugs.collectEntries { jiraBug ->
            def bug = [
                key: jiraBug.key,
                name: jiraBug.fields.summary,
                assignee: jiraBug.fields.assignee ? [jiraBug.fields.assignee.displayName, jiraBug.fields.assignee.name, jiraBug.fields.assignee.emailAddress].find { it != null } : "Unassigned",
                dueDate: '', // TODO: currently unsupported for not being enabled on a Bug issue
                status: jiraBug.fields.status.name,
            ]

            def testKeys = []
            if (jiraBug.fields.issuelinks) {
                testKeys = jiraBug.fields.issuelinks.findAll {
                    it.type.name == 'Blocks' && it.outwardIssue &&
                    it.outwardIssue.fields.issuetype.name == 'Test' }.collect { it.outwardIssue.key }
            }

            // Add relations from bug to tests
            bug.tests = testKeys

            // Add relations from tests to bug
            testKeys.each { testKey ->
                if (!tests[testKey].bugs) {
                    tests[testKey].bugs = []
                }

                tests[testKey].bugs << bug.key
            }

            return [jiraBug.key, bug]
        }
    }

    protected Map loadCurrentVersionDataFromJira() {
        loadVersionDataFromJira(this.buildParams.version)
    }

    protected Map loadVersionDataFromJira(String versionId) {
        if (!this.jiraUseCase) return [:]
        if (!this.jiraUseCase.jira) return [:]

        return this.jiraUseCase.jira.getVersionsForProject(this.jiraProjectKey).find { version ->
            versionId == version.value
        }
    }

    protected Map loadJiraDataDocs() {
        if (!this.jiraUseCase) return [:]
        if (!this.jiraUseCase.jira) return [:]

        def jqlQuery = [jql: "project = ${this.jiraProjectKey} AND issuetype = '${JiraUseCase.IssueTypes.DOCUMENTATION_TRACKING}'"]

        def jiraIssues = this.jiraUseCase.jira.getIssuesForJQLQuery(jqlQuery)
        if (jiraIssues.isEmpty()) {
            throw new IllegalArgumentException(
                "Error: Jira data does not include references to items of type '${JiraDataItem.TYPE_DOCS}'.")
        }

        return jiraIssues.collectEntries { jiraIssue ->
            [
                jiraIssue.key,
                [
                    key: jiraIssue.key,
                    name: jiraIssue.fields.summary,
                    description: jiraIssue.fields.description,
                    status: jiraIssue.fields.status.name,
                    labels: jiraIssue.fields.labels,
                ],
            ]
        }
    }

    protected Map loadJiraDataIssueTypes() {
        if (!this.jiraUseCase) return [:]
        if (!this.jiraUseCase.jira) return [:]

        def jiraIssueTypes = this.jiraUseCase.jira.getIssueTypes(this.jiraProjectKey)
        return jiraIssueTypes.values.collectEntries { jiraIssueType ->
            [
                jiraIssueType.name,
                [
                    id: jiraIssueType.id,
                    name: jiraIssueType.name,
                    fields: this.jiraUseCase.jira.getIssueTypeMetadata(this.jiraProjectKey, jiraIssueType.id).values.collectEntries { value ->
                        [
                            value.name,
                            [
                                id:   value.fieldId,
                                name: value.name,
                            ]
                        ]
                    }
                ],
            ]
        }
    }

    protected Map loadMetadata(String filename = METADATA_FILE_NAME) {
        if (filename == null) {
            throw new IllegalArgumentException("Error: unable to parse project meta data. 'filename' is undefined.")
        }

        def file = Paths.get(this.steps.env.WORKSPACE, filename).toFile()
        if (!file.exists()) {
            throw new RuntimeException("Error: unable to load project meta data. File '${this.steps.env.WORKSPACE}/${filename}' does not exist.")
        }

        def result = new Yaml().load(file.text)

        // Check for existence of required attribute 'id'
        if (!result?.id?.trim()) {
            throw new IllegalArgumentException("Error: unable to parse project meta data. Required attribute 'id' is undefined.")
        }

        // Check for existence of required attribute 'name'
        if (!result?.name?.trim()) {
            throw new IllegalArgumentException("Error: unable to parse project meta data. Required attribute 'name' is undefined.")
        }

        if (result.description == null) {
            result.description = ''
        }

        if (result.repositories == null) {
            result.repositories = []
        }

        result.repositories.eachWithIndex { repo, index ->
            // Check for existence of required attribute 'repositories[i].id'
            if (!repo.id?.trim()) {
                throw new IllegalArgumentException(
                    "Error: unable to parse project meta data. Required attribute 'repositories[${index}].id' is undefined.")
            }

            repo.data = [
                openshift: [:],
                documents: [:],
            ]

            // Set repo type, if not provided
            if (!repo.type?.trim()) {
                repo.type = MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_CODE
            }

            // Resolve repo URL, if not provided
            if (!repo.url?.trim()) {
                this.logger.debug("Could not determine Git URL for repo '${repo.id}' " +
                    'from project meta data. Attempting to resolve automatically...')

                def gitURL = this.getGitURLFromPath(this.steps.env.WORKSPACE, 'origin')
                if (repo.name?.trim()) {
                    repo.url = gitURL.resolve("${repo.name}.git").toString()
                    repo.remove('name')
                } else {
                    repo.url = gitURL.resolve("${result.id.toLowerCase()}-${repo.id}.git").toString()
                }

                this.logger.debug("Resolved Git URL for repo '${repo.id}' to '${repo.url}'")
            }

            // Resolve repo branch, if not provided
            if (!repo.branch?.trim()) {
                this.logger.debug("Could not determine Git branch for repo '${repo.id}' " +
                    "from project meta data. Assuming 'master'.")
                repo.branch = 'master'
            }
        }

        if (result.capabilities == null) {
            result.capabilities = []
        }

        // TODO move me to the LeVA documents plugin
        def levaDocsCapabilities = result.capabilities.findAll { it instanceof Map && it.containsKey('LeVADocs') }
        if (levaDocsCapabilities) {
            if (levaDocsCapabilities.size() > 1) {
                throw new IllegalArgumentException(
                    "Error: unable to parse project metadata. More than one 'LeVADoc' capability has been defined.")
            }

            def levaDocsCapability = levaDocsCapabilities.first()

            def gampCategory = levaDocsCapability.LeVADocs?.GAMPCategory
            if (!gampCategory) {
                throw new IllegalArgumentException(
                    "Error: 'LeVADocs' capability has been defined but contains no 'GAMPCategory'.")
            }

            def templatesVersion = levaDocsCapability.LeVADocs?.templatesVersion
            if (!templatesVersion) {
                levaDocsCapability.LeVADocs.templatesVersion = '1.0'
            }
        }

        if (result.environments == null) {
            result.environments = [:]
        }

        return result
    }

    public void reportPipelineStatus(String message = '', boolean isError = false) {
        if (!this.jiraUseCase) return
        this.jiraUseCase.updateJiraReleaseStatusResult(message, isError)
    }

    public void addCommentInReleaseStatus(String message) {
        if (!this.jiraUseCase) return
        this.jiraUseCase.addCommentInReleaseStatus(message)
    }

    @NonCPS
    protected Map resolveJiraDataItemReferences(Map data) {
        def result = [:]

        data.each { type, values ->
            if (!JiraDataItem.TYPES.contains(type)) {
                return
            }

            result[type] = [:]

            values.each { key, item ->
                result[type][key] = [:]

                JiraDataItem.TYPES.each { referenceType ->
                    if (item.containsKey(referenceType)) {
                        result[type][key][referenceType] = []

                        item[referenceType].eachWithIndex { referenceKey, index ->
                            result[type][key][referenceType][index] = data[referenceType][referenceKey]
                        }
                    }
                }
            }
        }

        return result
    }

    void setHasFailingTests(boolean status) {
        this.data.build.hasFailingTests = status
    }

    void setHasUnexecutedJiraTests(boolean status) {
        this.data.build.hasUnexecutedJiraTests = status
    }

    String toString() {
        // Don't serialize resolved Jira data items
        def result = this.data.subMap(['build', 'buildParams', 'metadata', 'git', 'jira'])

        if (!services?.jira && capabilities?.empty) {
            result.remove('jira')
        }

        return JsonOutput.prettyPrint(JsonOutput.toJson(result))
    }

    List<String> getMainReleaseManagerEnv () {
        def mroSharedLibVersion = this.steps.sh(
            script: "env | grep 'library.ods-mro-jenkins-shared-library.version' | cut -d= -f2",
            returnStdout: true,
            label: 'getting ODS shared lib version'
        ).trim()

        return [
            "ods.build.rm.${getKey()}.repo.url=${gitData.url}",
            "ods.build.rm.${getKey()}.repo.commit.sha=${gitData.commit}",
            "ods.build.rm.${getKey()}.repo.commit.msg=${gitData.message}",
            "ods.build.rm.${getKey()}.repo.commit.timestamp=${gitData.time}",
            "ods.build.rm.${getKey()}.repo.commit.author=${gitData.author}",
            "ods.build.rm.${getKey()}.repo.branch=${gitData.baseTag}",
            "ods.build.orchestration.lib.version=${mroSharedLibVersion}",
        ]
    }

    String getReleaseManagerBitbucketHostUrl () {
        return steps.env.BITBUCKET_URL ?: "https://${steps.env.BITBUCKET_HOST}"
    }

    String getTailorPrivateKeyCredentialsId() {
        def secretName = steps.env.TAILOR_PRIVATE_KEY_SECRET ?: 'tailor-private-key'
        "${getKey()}-cd-${secretName}"
    }

    String getSourceRegistrySecretName() {
        'mro-image-pull'
    }

    boolean getForceGlobalRebuild() {
        return (this.data.metadata.allowPartialRebuild &&
            this.config.get(NexusService.NEXUS_REPO_EXISTS_KEY, false)) ? false : true
    }

    void addConfigSetting (def key, def value) {
        this.config.put(key, value)
    }

    protected Map loadSavedJiraData(String savedVersion) {
        new ProjectDataBitbucketRepository(steps).loadFile(savedVersion)
    }

    /**
     * Saves the materialized jira data for this and old versions
     * @return File name created
     */
    String saveVersionData() {
        def savedEntities = ['components',
                             'mitigations',
                             'requirements',
                             'risks',
                             'tests',
                             'techSpecs',
                             'epics',
                             'version',
                             'predecessors',]
        def dataToSave = this.data.jira.findAll { savedEntities.contains(it.key) }
        logger.debug('Saving Jira data for the version ' + JsonOutput.toJson(this.getVersionName()))
        this.steps.echo('I am going to save the following ' + JsonOutput.toJson(dataToSave)) // TODO deleteme

        new ProjectDataBitbucketRepository(steps).save(dataToSave, this.getVersionName())
    }

    Map mergeJiraData(Map oldData, Map newData) {
        def mergeMaps = { Map left, Map right ->
            def keys = (left.keySet() + right.keySet()).toSet()

            keys.collectEntries { key ->
                if (!left[key] || left[key].isEmpty) {
                    [(key): right[key]]
                } else if (!right[key] || right[key].isEmpty) {
                    [(key): left[key]]
                } else {
                    [(key): left[key] + right[key]]
                }
            }
        }

        // Here we update the existing links in 3 ways:
        // - Deleting links of removing issues
        // - Adding links to new issues
        // - Updating links for changes in issues (changing key 1 for key 2)
        def updateIssues = { Map<String,Map> left, Map<String,Map> right ->
            def updateLink = { String issueType, String issueToUpdateKey, Map link ->
                if (link.action == 'add') {
                    left[issueType][issueToUpdateKey]."${link.linkType}" << link.origin
                } else if (link.action == 'discontinue') {
                    left[issueType][issueToUpdateKey]."${link.linkType}".removeAll{ it == link.origin }
                } else if (link.action == 'change') {
                    left[issueType][issueToUpdateKey]."${link.linkType}" << link.origin
                    left[issueType][issueToUpdateKey]."${link.linkType}".removeAll{ it == link."replaces" }
                }
            }

            def reverseLinkIndex = buildChangesInLinks(left, right)
            left.findAll { JiraDataItem.TYPES.contains(it.key) }.each { issueType, issues ->
                issues.values().each { Map issueToUpdate ->
                    def linksToUpdate = reverseLinkIndex.getOrDefault(issueToUpdate.key, [])
                    linksToUpdate.each { Map link ->
                        updateLink(issueType, issueToUpdate.key, link)
                    }
                }
            }
            return left
        }

        if (!oldData || oldData.isEmpty()) {
            newData
        } else {
            def discontinuations = newData.getOrDefault('discontinuations',[]) +
                this.getComponentDiscontinuations(oldData, newData)
            // Expand some information from old saved data
            def newDataExpanded = expandPredecessorInformation (oldData, newData)
            newDataExpanded << [discontinuationsPerType: discontinuationsPerType(oldData, discontinuations)]

            // Update data from previous version
            def oldDataWithUpdatedLinks = updateIssues(oldData, newDataExpanded)
            def obsoleteKeys = discontinuations + getPreceededKeys(newDataExpanded)
            def oldDataWithoutObsoletes = removeObsoleteIssues(oldDataWithUpdatedLinks, obsoleteKeys)

            mergeMaps(oldDataWithoutObsoletes, newDataExpanded)
        }
    }

    private static Map<String, List<String>> discontinuationsPerType (Map savedData, List<String> discontinuations) {
        savedData.findAll { JiraDataItem.TYPES.contains(it.key) }.collectEntries { String issueType, Map issues ->
            def discontinuationsPerType = issues.issues.findAll { discontinuations.contains(it.key) }
                .collect{ String issueKey, issue -> issueKey }
            [(issueType): discontinuationsPerType]
        }
    }

    private List<String> getComponentDiscontinuations(Map oldData, Map newData) {
        def oldComponents = oldData.getOrDefault(JiraDataItem.TYPE_COMPONENTS, [:]).keySet()
        def newComponents = newData.getOrDefault(JiraDataItem.TYPE_COMPONENTS, [:]).keySet()
        (oldComponents - newComponents) as List
    }

    private Map addVersionToComponentsWithout(Map jiraData) {
        def currentVersion = jiraData.version
        jiraData.getOrDefault(JiraDataItem.TYPE_COMPONENTS, [:]).values().each { component ->
            if (! component.version) {
                jiraData[JiraDataItem.TYPE_COMPONENTS][component.key].version = currentVersion
            }
        }
        jiraData
    }

    private static List getDiscontinuedLinks(Map savedData, List<String> discontinuations) {
        savedData.findAll { JiraDataItem.TYPES.contains(it.key) }.collect {
            issueType, Map issues ->
            def discontinuedLinks = issues.findAll { discontinuations.contains(it.key) }
                .collect { key, issue ->
                    def issueLinks = issue.findAll { JiraDataItem.TYPES.contains(it.key) }
                    issueLinks.collect { String linkType, List linkedIssues ->
                        linkedIssues.collect { targetKey ->
                            [origin: issue.key, target: targetKey, linkType: issueType, action: 'discontinue']
                        }
                    }.flatten()
                }.flatten()
            return discontinuedLinks
        }.flatten()
    }

    private static Map<String, List> buildChangesInLinks(Map oldData, Map updates) {
        def discontinuedLinks = getDiscontinuedLinks(oldData, updates.getOrDefault('discontinuations', []))
        def additionsAndChanges = getAdditionsAndChangesInLinks(updates)

        return (discontinuedLinks + additionsAndChanges).groupBy { it.target }
    }

    private static List getAdditionsAndChangesInLinks(Map newData) {
        def getLink = { String issueType, Map issue, String targetKey, Boolean isAnUpdate ->
            if (isAnUpdate) {
                issue.predecessors.collect {
                    [origin: issue.key, target: targetKey, linkType: issueType, action: 'change', replaces: it]
                }
            } else {
                [origin: issue.key, target: targetKey, linkType: issueType, action: 'add']
            }
        }

        newData.findAll { JiraDataItem.TYPES.contains(it.key) }.collect { issueType, issues ->
            issues.collect { String issueKey, Map issue ->
                def isAnUpdate = ! issue.getOrDefault('predecessors', []).isEmpty()

                def issueLinks = issue.findAll { JiraDataItem.TYPES.contains(it.key) }
                issueLinks.collect { String linkType, List linkedIssues ->
                    linkedIssues.collect { getLink(issueType, issue, it, isAnUpdate) }.flatten()
                }
            }
        }.flatten()
    }

    private static Map removeObsoleteIssues(Map jiraData, List<String> keysToRemove) {
        def result = jiraData.collectEntries { issueType, content ->
            if (JiraDataItem.TYPES.contains(issueType)) {
                [(issueType): content.findAll { ! keysToRemove.contains(it.key) } ]
            } else {
                [(issueType): content]
            }
        }
        return result
    }

    /**
     * Expected format is:
     *   issueType.issue."expandedPredecessors" -> [key:"", version:""]
     * @param jiraData map of jira data
     * @return keys of preceeded issues
     */
    private static List getPreceededKeys(Map jiraData) {
        jiraData.findAll { JiraDataItem.TYPES.contains(it.key) }.values().collect { issueGroup ->
            issueGroup.values().collect { issue ->
                [issue.getOrDefault('expandedPredecessors',[]).collect { it.key }]
            }
        }.flatten().unique()
    }

    /**
     * Recover the information about "preceding" issues for all the new ones that are an update on previously
     * released ones. That way we can provide all the changes in the documents
     * @param savedData data from old versions retrieved by the pipeline
     * @param newData data for the current version
     * @return Map new data with the issue predecessors expanded
     */
    private static Map expandPredecessorInformation(Map savedData, Map newData) {
        def expandPredecessor = { String issueType, String issueKey, String predecessor ->
            def predecessorIssue = savedData.getOrDefault(issueType, [:]).getOrDefault(predecessor, null)
            if (!predecessorIssue) {
                throw new RuntimeException("Error: new issue '${issueKey}' references key '${predecessor}' " +
                    "of type '${issueType}' that cannot be found in the saved data for version '${savedData.version}'." +
                    "Existing issue list is '[${savedData.getOrDefault(issueType, [:]).keySet().join(', ')}]'")
            }
            def existingPredecessors = predecessorIssue.getOrDefault('expandedPredecessors', [:])
            def result = [[key: predecessorIssue.key, version: predecessorIssue.version]]

            if (existingPredecessors) {
                result << existingPredecessors
            }
            result.flatten()
        }

        newData.collectEntries { issueType, content ->
            if (JiraDataItem.TYPES.contains(issueType)) {
                def updatedIssues = content.collectEntries { String issueKey, Map issue ->
                    def predecessors = issue.getOrDefault('predecessors', [])
                    if (predecessors.isEmpty()) {
                        [(issueKey): issue]
                    } else {
                        def expandedPredecessors = predecessors.collect { predecessor ->
                            expandPredecessor(issueType, issueKey, predecessor)
                        }.flatten()
                        [(issueKey): (issue + [expandedPredecessors: expandedPredecessors])]
                    }
                }
                [(issueType): updatedIssues]
            } else {
                [(issueType): content]
            }
        }
    }

}

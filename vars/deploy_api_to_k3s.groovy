// vars/deploy_api_to_k3s.groovy
import groovy.json.JsonOutput

def call(Map config = [:]) {
    pipeline {
        agent { label config.exe_node ?: 'w-ubuntu' }
        options {
            timestamps()
            skipDefaultCheckout(true)
            disableConcurrentBuilds()
            timeout(time: config.timeoutMinutes ?: 60, unit: 'MINUTES')
        }
        environment {
            http_proxy  = ''
            https_proxy = ''
            HTTP_PROXY  = ''
            HTTPS_PROXY = ''
        }
        parameters {
            string(name: 'GIT_URL', defaultValue: config.gitUrl ?: '', description: 'Git repository URL')
            string(name: 'exe_node', defaultValue: config.exe_node ?: 'w-ubuntu', description: 'Execution node')
            string(name: 'branch', defaultValue: config.branch ?: 'main', description: 'Git branch')
            string(name: 'envs', defaultValue: config.envs ?: '', description: 'Environment variables as JSON')
            string(name: 'api_id', defaultValue: config.api_id ?: '', description: 'API ID')
            string(name: 'gitToken', defaultValue: config.gitToken ?: '', description: 'Git token for authentication')
            string(name: 'GIT_CREDENTIALS_ID', defaultValue: config.gitCredentialsId ?: '', description: 'Jenkins Git credentials ID')
            string(name: 'api_name', defaultValue: config.api_name ?: '', description: 'API name / Helm release name')
            string(name: 'CALL_BACK_HOST', defaultValue: config.call_back_host ?: '', description: '构建完成后的回调地址')
            string(name: 'HELM_GIT_URL', defaultValue: config.helmGitUrl ?: 'https://github.com/jiangchengyu998/devops-learn.git', description: 'Helm charts repository URL')
            string(name: 'HELM_GIT_BRANCH', defaultValue: config.helmGitBranch ?: 'main', description: 'Helm charts repository branch')
            string(name: 'HELM_ENV', defaultValue: config.helmEnv ?: 'dev', description: 'Helm environment value')
            string(name: 'HELM_HOST_SUFFIX', defaultValue: config.helmHostSuffix ?: 'ydphoto.com', description: 'Ingress host suffix')
            string(name: 'HELM_CHART', defaultValue: config.helmChart ?: '', description: 'Helm chart name, auto-detect when empty')
        }

        stages {
            stage('Prepare Workspace') {
                steps {
                    script {
                        validateRequiredParams()
                        echo 'Cleaning workspace...'
                        deleteDir()
                        echo 'Workspace cleaned successfully'
                    }
                }
            }

            stage('Checkout') {
                steps {
                    script {
                        env.REPO_NAME = repoNameFromGitUrl(params.GIT_URL)
                        echo "Repository name: ${env.REPO_NAME}"

                        dir(env.REPO_NAME) {
                            checkoutApplicationCode()

                            def lastCommit = sh(
                                    script: 'git log --oneline -1',
                                    returnStdout: true
                            ).trim()
                            echo "Latest commit: ${lastCommit}"
                        }
                    }
                }
            }

            stage('CI') {
                steps {
                    script {
                        def codeDir = "${pwd()}/${env.REPO_NAME}"

                        echo "Starting image build..."
                        echo "Code directory: ${codeDir}"

                        writeFile file: 'deploy.sh', text: libraryResource('deploy.sh')
                        writeFile file: 'Dockerfile_java8', text: libraryResource('Dockerfile_java8')
                        sh 'chmod +x deploy.sh'

                        def projectInfo = detectProjectInfo(codeDir)
                        env.VERSION = projectInfo.version
                        env.PROJECT_LANGUAGE = projectInfo.language

                        echo "Detected project language: ${env.PROJECT_LANGUAGE}"
                        echo "Image version: ${env.VERSION}"

                        withCredentials([usernamePassword(credentialsId: '097d9c91-53ff-4068-8a37-9b5a3cd7485d', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                            withEnv([
                                    "CODE_DIR=${codeDir}",
                                    "APP_ENVS=${params.envs ?: ''}",
                                    "API_NAME=${params.api_name}",
                                    "IMAGE_VERSION=${env.VERSION}"
                            ]) {
                                sh(
                                        label: 'Build and push Docker image',
                                        script: '''
                                            set -e
                                            ./deploy.sh "$CODE_DIR" "$APP_ENVS" "$API_NAME" "$USERNAME" "$PASSWORD" "$IMAGE_VERSION"
                                        '''.stripIndent()
                                )
                            }
                        }

                        env.CONTAINER_PORT = detectExposedPort("${codeDir}/Dockerfile")
                        echo "Detected container EXPOSE port: ${env.CONTAINER_PORT}"

                    }
                }
            }

            stage('CD') {
                steps {
                    script {
                        dir('devops-learn') {
                            git(
                                    url: params.HELM_GIT_URL,
                                    branch: params.HELM_GIT_BRANCH
                            )
                        }

                        def chartsDir = "${env.WORKSPACE}/devops-learn/charts"
                        def chartName = params.HELM_CHART?.trim()
                        if (!chartName) {
                            chartName = env.PROJECT_LANGUAGE == 'java' ? 'springboot-api' : 'generic-api'
                        }
                        def chartPath = "${chartsDir}/${chartName}"

                        sh(
                                label: 'Validate Helm chart path',
                                script: """
                                    set -e
                                    test -d ${shellQuote(chartsDir)}
                                    test -f ${shellQuote(chartPath + '/values.yaml')}
                                """.stripIndent()
                        )

                        writeFile file: 'deploy_helm.sh', text: libraryResource('deploy_helm.sh')
                        sh 'chmod +x deploy_helm.sh'

                        def host = buildHost(params.api_name, params.HELM_HOST_SUFFIX)
                        def releaseName = params.api_name.trim()
                        def envName = params.HELM_ENV.trim()

                        echo """
                            ===== Helm Deploy =====
                            chartPath: ${chartPath}
                            host: ${host}
                            releaseName: ${releaseName}
                            version: ${env.VERSION}
                            environment: ${envName}
                            containerPort: ${env.CONTAINER_PORT}
                            =======================
                        """.stripIndent()

                        withEnv([
                                "CHART_PATH=${chartPath}",
                                "HELM_HOST=${host}",
                                "RELEASE_NAME=${releaseName}",
                                "IMAGE_VERSION=${env.VERSION}",
                                "HELM_ENV_NAME=${envName}",
                                "HELM_CONTAINER_PORT=${env.CONTAINER_PORT}"
                        ]) {
                            sh(
                                    label: 'Deploy Helm release',
                                    script: '''
                                        set -e
                                        ./deploy_helm.sh "$CHART_PATH" "$HELM_HOST" "$RELEASE_NAME" "$IMAGE_VERSION" "$HELM_ENV_NAME"
                                    '''.stripIndent()
                            )
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Deployment api ${params.api_id} completed successfully on container port ${env.CONTAINER_PORT}"
                script {
                    echo '准备发送 RUNNING 状态的 webhook 回调'
                    sendWebhookWithRetry(params.api_id, 'RUNNING', env.BUILD_ID, 3, params.CALL_BACK_HOST)
                }
            }
            failure {
                echo 'Deployment failed'
                script {
                    echo '准备发送 ERROR 状态的 webhook 回调'
                    sendWebhookWithRetry(params.api_id, 'ERROR', env.BUILD_ID, 3, params.CALL_BACK_HOST)
                }
            }
        }
    }
}

def validateRequiredParams() {
    def required = [
            GIT_URL: params.GIT_URL,
            branch: params.branch,
            api_name: params.api_name,
            HELM_GIT_URL: params.HELM_GIT_URL,
            HELM_GIT_BRANCH: params.HELM_GIT_BRANCH,
            HELM_ENV: params.HELM_ENV,
            HELM_HOST_SUFFIX: params.HELM_HOST_SUFFIX
    ]

    def missing = required.findAll { !it.value?.trim() }.keySet()
    if (missing) {
        error "Missing required parameters: ${missing.join(', ')}"
    }

    validateKubernetesName(params.api_name, 'api_name')
}

def checkoutApplicationCode() {
    if (params.GIT_CREDENTIALS_ID?.trim()) {
        git(
                branch: params.branch,
                url: params.GIT_URL,
                credentialsId: params.GIT_CREDENTIALS_ID.trim()
        )
        return
    }

    if (params.gitToken?.trim()) {
        echo 'Using token authentication for Git clone'
        withEnv([
                "SOURCE_GIT_URL=${params.GIT_URL}",
                "SOURCE_GIT_BRANCH=${params.branch}",
                "SOURCE_GIT_TOKEN=${params.gitToken}"
        ]) {
            sh(
                    label: 'Clone repository with token',
                    script: '''
                        set +x
                        case "$SOURCE_GIT_URL" in
                          https://*) git_url_without_scheme="${SOURCE_GIT_URL#https://}" ;;
                          http://*) git_url_without_scheme="${SOURCE_GIT_URL#http://}" ;;
                          *) echo "gitToken authentication requires an http(s) Git URL" >&2; exit 2 ;;
                        esac
                        git clone --branch "$SOURCE_GIT_BRANCH" --single-branch "https://oauth2:${SOURCE_GIT_TOKEN}@${git_url_without_scheme}" .
                    '''.stripIndent()
            )
        }
        return
    }

    echo 'No Git credentials provided, using original Git URL'
    git branch: params.branch, url: params.GIT_URL
}

def detectProjectInfo(String codeDir) {
    def quotedDir = shellQuote(codeDir)
    def version = null
    def language = 'other'

    if (fileExists("${codeDir}/pom.xml")) {
        version = sh(
                script: "grep -m1 '<version>' ${shellQuote(codeDir + '/pom.xml')} | sed 's/.*<version>\\(.*\\)<\\/version>.*/\\1/'",
                returnStdout: true
        ).trim()
        language = 'java'
    } else if (fileExists("${codeDir}/build.gradle") || fileExists("${codeDir}/build.gradle.kts")) {
        version = sh(
                script: "cd ${quotedDir} && git describe --tags --always --dirty",
                returnStdout: true
        ).trim()
        language = 'java'
    } else {
        version = sh(
                script: "cd ${quotedDir} && git describe --tags --always --dirty || true",
                returnStdout: true
        ).trim()
    }

    version = sanitizeDockerTag(version ?: 'latest')
    return [version: version, language: language]
}

def detectExposedPort(String dockerfilePath) {
    if (!fileExists(dockerfilePath)) {
        error "Dockerfile not found when detecting EXPOSE port: ${dockerfilePath}"
    }

    def variables = [:]

    def logicalLines = []
    def currentLine = ''
    readFile(file: dockerfilePath).readLines().each { rawLine ->
        def line = rawLine.replaceFirst(/\s+#.*$/, '').trim()
        if (!line) {
            return
        }

        if (line.endsWith('\\')) {
            currentLine += line[0..-2].trim() + ' '
            return
        }

        logicalLines << (currentLine + line).trim()
        currentLine = ''
    }
    if (currentLine.trim()) {
        logicalLines << currentLine.trim()
    }

    logicalLines.each { line ->
        def argMatcher = line =~ /(?i)^ARG\s+([A-Za-z_][A-Za-z0-9_]*)(?:=(\S+))?$/
        if (argMatcher.matches() && !variables[argMatcher[0][1]] && argMatcher[0][2]) {
            variables[argMatcher[0][1]] = argMatcher[0][2]
        }

        def envMatcher = line =~ /(?i)^ENV\s+(.+)$/
        if (envMatcher.matches()) {
            envMatcher[0][1].split(/\s+/).each { entry ->
                def keyValue = entry.split('=', 2)
                if (keyValue.length == 2 && keyValue[0]) {
                    variables[keyValue[0]] = resolveDockerfileValue(keyValue[1], variables)
                }
            }
        }
    }

    def exposeLines = logicalLines.findAll { it ==~ /(?i)^EXPOSE\s+.+/ }
    if (!exposeLines) {
        error "Dockerfile must declare an EXPOSE port: ${dockerfilePath}"
    }

    def exposeValue = exposeLines.last().replaceFirst(/(?i)^EXPOSE\s+/, '').trim().tokenize().first()
    exposeValue = exposeValue.replaceFirst(/(?i)\/(tcp|udp)$/, '').replaceAll(/^['"]|['"]$/, '')
    def resolvedPort = resolveDockerfileValue(exposeValue, variables)
    return validatePortValue(resolvedPort, "Dockerfile EXPOSE port (${exposeValue})")
}

def resolveDockerfileValue(String value, Map variables, Set resolving = [] as Set) {
    def result = (value ?: '').trim().replaceAll(/^['"]|['"]$/, '')
    def defaultMatcher = result =~ /^\$\{([A-Za-z_][A-Za-z0-9_]*):-([^}]+)\}$/
    if (defaultMatcher.matches()) {
        def name = defaultMatcher[0][1]
        if (resolving.contains(name)) {
            return defaultMatcher[0][2]
        }
        def resolved = variables[name] ?: defaultMatcher[0][2]
        return resolveDockerfileValue(resolved, variables, resolving + name)
    }

    def bracedMatcher = result =~ /^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$/
    if (bracedMatcher.matches()) {
        def name = bracedMatcher[0][1]
        if (resolving.contains(name)) {
            return result
        }
        def resolved = variables[name]
        return resolved ? resolveDockerfileValue(resolved, variables, resolving + name) : result
    }

    def plainMatcher = result =~ /^\$([A-Za-z_][A-Za-z0-9_]*)$/
    if (plainMatcher.matches()) {
        def name = plainMatcher[0][1]
        if (resolving.contains(name)) {
            return result
        }
        def resolved = variables[name]
        return resolved ? resolveDockerfileValue(resolved, variables, resolving + name) : result
    }

    return result
}

def validatePortValue(String value, String label) {
    def port = (value ?: '').trim()
    if (!(port ==~ /^[0-9]{1,5}$/) || port.toInteger() < 1 || port.toInteger() > 65535) {
        error "Invalid ${label}: ${value}"
    }
    return port
}

def repoNameFromGitUrl(String gitUrl) {
    def cleanUrl = gitUrl.trim().replaceAll(/[?#].*$/, '').replaceAll(/\/+$/, '')
    def repoName = cleanUrl.tokenize('/').last().replaceFirst(/\.git$/, '')
    repoName = repoName.replaceAll(/[^A-Za-z0-9_.-]/, '-')
    if (!repoName) {
        error "Unable to determine repository name from GIT_URL: ${gitUrl}"
    }
    return repoName
}

def buildHost(String apiName, String suffix) {
    def cleanSuffix = suffix.trim().replaceFirst(/^\.+/, '')
    return "${apiName.trim()}.${cleanSuffix}"
}

def validateKubernetesName(String value, String label) {
    def name = value?.trim()
    if (!(name ==~ /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/)) {
        error "${label} must be a valid Kubernetes DNS label: ${value}"
    }
}

def sanitizeDockerTag(String value) {
    def tag = value.trim().replaceAll(/[^A-Za-z0-9_.-]/, '-')
    if (!tag) {
        return 'latest'
    }
    return tag.take(128)
}

def shellQuote(String value) {
    return "'" + (value ?: '').replace("'", "'\"'\"'") + "'"
}

def sendWebhookWithRetry(apiId, status, jobId, maxRetries = 3, callBackHost) {
    if (!apiId?.trim() || !callBackHost?.trim()) {
        echo 'Webhook skipped: api_id or CALL_BACK_HOST is empty'
        return false
    }

    def retryDelay = 5
    def payloadFile = "webhook-${status.toLowerCase()}-${jobId}.json"
    writeFile file: payloadFile, text: JsonOutput.toJson([apiStatus: status, jobId: jobId])
    def webhookUrl = "${callBackHost.replaceAll(/\/+$/, '')}/api/apis/${apiId}/webhook"

    for (int i = 0; i < maxRetries; i++) {
        try {
            echo "Sending ${status} webhook - Attempt ${i + 1}/${maxRetries}"

            def result = sh(
                    script: """curl --location ${shellQuote(webhookUrl)} \\
                    --header 'Content-Type: application/json' \\
                    --data @${shellQuote(payloadFile)} \\
                    --insecure \\
                    --ssl-no-revoke \\
                    --connect-timeout 10 \\
                    --max-time 30 \\
                    --silent \\
                    --show-error""",
                    returnStatus: true
            )

            if (result == 0) {
                echo "${status} webhook sent successfully"
                return true
            }

            echo "Webhook attempt ${i + 1} failed with exit code: ${result}"
            if (i < maxRetries - 1) {
                sleep(retryDelay)
            }
        } catch (Exception e) {
            echo "Webhook attempt ${i + 1} threw exception: ${e.message}"
            if (i < maxRetries - 1) {
                sleep(retryDelay)
            }
        }
    }

    echo "Failed to send ${status} webhook after ${maxRetries} attempts"
    return false
}

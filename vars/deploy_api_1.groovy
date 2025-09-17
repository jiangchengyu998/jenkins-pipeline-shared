// vars/deploy_api.groovy
def call(Map config = [:]) {
    // 设置默认值
    def gitUrl = config.gitUrl ?: error('gitUrl parameter is required')
    def apiPort = config.apiPort ?: '3000'
    def branch = config.branch ?: 'main'

    // 在指定的节点上运行部署流程
    node {
        stage('Prepare Workspace') {
            deleteDir() // 清空workspace
        }

        stage('Checkout') {
            // 自动提取仓库名
            def repoName = gitUrl.tokenize('/').last().replace('.git', '')
            // checkout代码到特定目录
            dir(repoName) {
                git branch: branch, url: gitUrl
            }
        }

        stage('Deploy') {
            // 获取仓库目录
            def repoName = gitUrl.tokenize('/').last().replace('.git', '')
            def codeDir = "${pwd()}/${repoName}"

            // 从shared library resources中加载deploy.sh
            def scriptContent = libraryResource('deploy.sh')
            writeFile file: 'deploy.sh', text: scriptContent
            sh 'chmod +x deploy.sh'

            // 执行部署脚本
            sh "./deploy.sh ${codeDir} ${apiPort}"

            // 输出调试信息
            sh "ls -l"
            sh "pwd"
            sh "docker ps"
        }
    }
}
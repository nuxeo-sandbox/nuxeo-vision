/*
 * (C) Copyright 2020-2021 Nuxeo (http://nuxeo.com/).
 * This is unpublished proprietary source code of Nuxeo SA. All rights reserved.
 * Notice of copyright on this source code does not indicate publication.
 *
 * Contributors:
 *     Julien Carsique <jcarsique@nuxeo.com>
 */

void setGitHubBuildStatus(String context) {
    step([
            $class       : 'GitHubCommitStatusSetter',
            reposSource  : [$class: 'ManuallyEnteredRepositorySource', url: 'https://github.com/nuxeo/nuxeo-vision'],
            contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: context],
            errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
    ])
}

String getMavenArgs() {
    def args = '-V -B -Pmarketplace,ftest,aws clean install'
    if (env.TAG_NAME) {
        args += ' deploy -P-nexus,release -DskipTests'
    } else if (env.BRANCH_NAME ==~ 'master.*') {
        args += ' deploy -P-nexus'
    } else if (env.BRANCH_NAME ==~ 'sprint.*') {
        args += ' deploy -Pnexus'
    } else {
        args += ' package'
    }
    return args
}

/**
 * Normalize a string as a K8s namespace.
 * The pattern is '[a-z0-9]([-a-z0-9]*[a-z0-9])?' with a max length of 63 characters.
 */
String normalizeNS(String namespace) {
    namespace = namespace.trim().substring(0, Math.min(namespace.length(), 63)).toLowerCase().replaceAll("[^-a-z0-9]", "-")
    assert namespace ==~ /[a-z0-9]([-a-z0-9]*[a-z0-9])?/
    assert namespace.length() <= 63
    return namespace
}

/**
 * Normalize a string as a K8s label (prefix excluded).
 * The pattern is '<prefix/>?[0-9A-Za-z\-._]+' with a max length of 63 characters after the prefix.
 * 'jx preview' sets a default label '<Git Organisation> + "/" + <Git Name> + " PR-" + <PullRequestName?:-env.BRANCH_NAME>'.
 * Here we want to normalize the branch name.
 *
 */
String normalizeLabel(String branchName) {
    int maxLength = 63 - "nuxeo/nuxeo-vision PR-".length()
    branchName = branchName.trim().substring(0, Math.min(branchName.length(), maxLength)).replaceAll("[^-._a-z0-9A-Z]", "-")
    assert branchName ==~ /[a-z0-9A-Z][-._0-9A-Za-z]*[a-z0-9A-Z]/
    assert branchName.length() <= maxLength
    return branchName
}

String getVersion() {
    String version = readMavenPom().getVersion()
    version = env.TAG_NAME ? version : version + "-" + env.BRANCH_NAME.replace('/', '-')
    assert version ==~ /[0-9A-Za-z\-._]*/
    return version
}

/**
 * Wait for Nuxeo Kubernetes application's deployment, then wait for the pod being effectively ready
 * and finally check the running status.
 * In case of error, debug information is logged.
 * @param name Nuxeo app name
 * @param url Nuxeo URL
 */
def void waitForNuxeo(String name, String url, Closure body = null) {
    script {
        try {
            body?.call()
            echo "Check deployment and running status for $name at $url ..."
            sh "kubectl -n ${PREVIEW_NAMESPACE} rollout status deployment $name"
            sh "kubectl -n ${PREVIEW_NAMESPACE} wait --for=condition=ready pod -l app=$name --timeout=-0"
            sh "curl --retry 10 -fsSL $url/nuxeo/runningstatus"
        } catch (e) {
            sh "jx get preview  -o json |jq '.items|map(select(.spec.namespace==\"${PREVIEW_NAMESPACE}\"))' 2>&1 |tee debug-${name}.log"
            sh "kubectl -n ${PREVIEW_NAMESPACE} get all,configmaps,endpoints,ingresses 2>&1 |tee -a debug-${name}.log"
            sh "kubectl -n ${PREVIEW_NAMESPACE} describe pod --selector=app=$name 2>&1 |tee -a debug-${name}.log"
            sh "kubectl -n ${PREVIEW_NAMESPACE} logs --selector=app=$name --all-containers --tail=-1 2>&1 |tee -a debug-${name}.log"
            echo "See debug info in debug-${name}.log"
            throw e
        }
    }
}

pipeline {
    agent {
        label "jenkins-ai-nuxeo11"
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5'))
        timeout(time: 1, unit: 'HOURS')
    }
    parameters {
        choice(name: 'PROVIDER', choices: ['google', 'aws'],
                description: '''Choose the provider for Nuxeo Vision ('org.nuxeo.vision.default.provider' property)''')
    }
    environment {
        ORG = 'nuxeo'
        APP_NAME = 'nuxeo-vision'
        WEBUI_VERSION = readMavenPom().getProperties().getProperty('nuxeo.webui.version')
        PLATFORM_VERSION = ''
        SCM_REF = "${sh(script: 'git show -s --pretty=format:\'%H%d\'', returnStdout: true).trim();}"
        PREVIEW_NAMESPACE = normalizeNS("$APP_NAME-$BRANCH_NAME")
        PREVIEW_URL = "https://preview-${PREVIEW_NAMESPACE}.ai.dev.nuxeo.com"
        VERSION = getVersion()
        MARKETPLACE_URL = 'https://connect.nuxeo.com/nuxeo/site/marketplace'
        MARKETPLACE_URL_PREPROD = 'https://nos-preprod-connect.nuxeocloud.com/nuxeo/site/marketplace'
    }
    stages {
        stage('Init') {
            steps {
                setGitHubBuildStatus('init')
                setGitHubBuildStatus('maven/build')
                setGitHubBuildStatus('docker/build')
                script {
                    if (env.BRANCH_NAME == 'master' || env.BRANCH_NAME ==~ 'sprint-.*' || env.CHANGE_BRANCH) {
                        setGitHubBuildStatus('preview')
                    }
                    if (env.BRANCH_NAME == 'master' || env.BRANCH_NAME ==~ 'sprint-.*' || env.TAG_NAME) {
                        setGitHubBuildStatus('package/push')
                    }
                }
                container('platform11') {
                    sh """#!/bin/bash
jx step git credentials
git config credential.helper store
"""
                    sh """
# skaffold
curl -f -Lo skaffold https://storage.googleapis.com/skaffold/releases/v1.14.0/skaffold-linux-amd64
chmod +x skaffold
mv skaffold /usr/bin/

# reg: Docker registry v2 command line client
REG_SHA256="ade837fc5224acd8c34732bf54a94f579b47851cc6a7fd5899a98386b782e228"
curl --retry 5 -fsSL "https://github.com/genuinetools/reg/releases/download/v0.16.1/reg-linux-amd64" -o /usr/bin/reg
echo "\${REG_SHA256} /usr/bin/reg" | sha256sum -c - && chmod +x /usr/bin/reg
"""
                    script {
                        PLATFORM_VERSION = sh(script: 'mvn help:evaluate -Dexpression=nuxeo.platform.version -q -DforceStdout', returnStdout: true).trim()
                        if (env.CHANGE_TARGET) {
                            echo "PR build: cleaning up the branch artifacts..."
                            sh """
reg rm "${DOCKER_REGISTRY}/${ORG}/${APP_NAME}:${VERSION}" || true
"""
                        }
                    }
                    echo "PLATFORM_VERSION: $PLATFORM_VERSION"
                    echo "VERSION: $VERSION"
                }
            }
            post {
                always {
                    setGitHubBuildStatus('init')
                }
            }
        }
        stage('Maven Build') {
            environment {
                MAVEN_OPTS = "-Xms512m -Xmx1g"
                MAVEN_ARGS = getMavenArgs()
                AWS_REGION = "us-east-1"
            }
            steps {
                container('platform11') {
//                    withAWS(region: AWS_REGION, credentials: 'aws-762822024843-jenkins-nuxeo-ai') { // jenkinsci/pipeline-aws-plugin#151
                    withCredentials([[$class       : 'AmazonWebServicesCredentialsBinding',
                                      credentialsId: 'aws-762822024843-jenkins-nuxeo-ai'],
                                     file(credentialsId: 'google-vision', variable: 'NUXEO_VISION_JSON')]) {
                        sh 'mvn ${MAVEN_ARGS} -Dorg.nuxeo.vision.test.credential.file=${NUXEO_VISION_JSON}'
                    }
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/*-reports/*.xml'
                    archiveArtifacts artifacts: '**/log/*.log, **/nxserver/config/distribution.properties, ' +
                            '**/target/*-reports/*, **/target/results/*.html, **/target/*.png, **/target/*.html' +
                            'nuxeo-vision-marketplace/target/nuxeo-vision-marketplace-*.zip',
                            allowEmptyArchive: true
                    setGitHubBuildStatus('maven/build')
                }
            }
        }
        stage('Docker Build') {
            steps {
                container('platform11') {
                    sh "cp nuxeo-vision-marketplace/target/nuxeo-vision-marketplace-*.zip docker/"
                    withEnv(["PLATFORM_VERSION=${PLATFORM_VERSION}"]) {
                        withCredentials([usernameColonPassword(credentialsId: 'connect-nuxeo-ai-jx-bot', variable: 'CONNECT_CREDS_PROD'),
                                         usernameColonPassword(credentialsId: 'connect-preprod', variable: 'CONNECT_CREDS_PREPROD')]) {
                            sh '''
curl -fsSL -u "$CONNECT_CREDS_PREPROD" "$MARKETPLACE_URL_PREPROD/package/nuxeo-web-ui/download?version=$WEBUI_VERSION" -o docker/nuxeo-web-ui.zip --retry 10 --retry-max-time 600
'''
                        }
                        dir('docker') {
                            echo "Build preview image"
                            sh 'printenv|sort|grep VERSION'
                            sh """
envsubst < skaffold.yaml > skaffold.yaml~gen
skaffold build -f skaffold.yaml~gen
"""
                        }
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'docker/skaffold.yaml~gen'
                    setGitHubBuildStatus('docker/build')
                }
            }
        }
        stage('Deploy Preview') {
            when {
                anyOf {
                    branch 'master*'
                    branch 'sprint-*'
                    changeRequest()
                }
            }
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            environment {
                AWS_REGION = "eu-west-1"
                JX_NO_COMMENT = "${env.CHANGE_TARGET ? 'false' : 'true'}"
            }
            steps {
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    container('platform11') {
                        withCredentials([[$class       : 'AmazonWebServicesCredentialsBinding',
                                          credentialsId: 'aws-762822024843-jenkins-nuxeo-ai']]) {
                            withEnv(["PREVIEW_VERSION=$VERSION", "BRANCH_NAME=${normalizeLabel(BRANCH_NAME)}",
                                     "PROVIDER=${params.PROVIDER}"]) {
                                waitForNuxeo("preview", PREVIEW_URL) {
                                    dir('charts/preview') {
                                        sh """#!/bin/bash -xe
kubectl delete ns ${PREVIEW_NAMESPACE} --ignore-not-found=true
kubectl create ns ${PREVIEW_NAMESPACE}
make preview

# detach process that would never succeed to patch the deployment, then reattach
jx preview --namespace ${PREVIEW_NAMESPACE} --verbose --source-url=$GIT_URL --preview-health-timeout 15m --alias nuxeo --no-comment=$JX_NO_COMMENT &
until (kubectl -n ${PREVIEW_NAMESPACE} get deploy preview 2>/dev/null); do sleep 5; done
kubectl -n ${PREVIEW_NAMESPACE} scale deployment --replicas=0 preview
kubectl -n ${PREVIEW_NAMESPACE} patch deployments preview --patch "\$(cat patch-preview.yaml)"
kubectl -n ${PREVIEW_NAMESPACE} scale deployment --replicas=1 preview
wait
"""
                                    }
                                }
                            }
                        }
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'charts/preview/values.yaml, charts/preview/extraValues.yaml, ' +
                            'charts/preview/requirements.lock, charts/preview/.previewUrl, debug-preview.log'
                    setGitHubBuildStatus('preview')
                }
            }
        }
        stage('Push Packages') {
            when {
                anyOf {
                    tag '*'
                    branch 'master*'
                    branch 'sprint-*'
                }
            }
            environment {
                PACKAGE_PATTERN = 'nuxeo-vision-marketplace/target/nuxeo-vision-marketplace-*.zip'
            }
            steps {
                container('platform11') {
                    withCredentials([usernameColonPassword(credentialsId: 'connect-nuxeo-ai-jx-bot', variable: 'CONNECT_CREDS'),
                                     usernameColonPassword(credentialsId: 'connect-preprod', variable: 'CONNECT_CREDS_PREPROD')]) {
                        sh '''
PACKAGES="\$(ls $PACKAGE_PATTERN)"
for file in \$PACKAGES ; do
    curl --fail -u "$CONNECT_CREDS_PREPROD" -F package=@\$file "$MARKETPLACE_URL_PREPROD/upload?batch=true" || true
    curl --fail -u "$CONNECT_CREDS" -F package=@\$file "$MARKETPLACE_URL/upload?batch=true"
done
'''
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: PACKAGE_PATTERN.replaceAll(' ', ', '), allowEmptyArchive: false
                    setGitHubBuildStatus('package/push')
                }
            }
        }
        stage('Upgrade version stream') {
            when {
                tag '*'
            }
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                    container('platform11') {
                        sh """#!/bin/bash -xe
jx step create pr regex --regex 'version: (.*)' --version $VERSION --files packages/nuxeo-vision.yml -r https://github.com/nuxeo/jx-ai-versions
"""
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                if (env.BRANCH_NAME ==~ 'master.*' || env.TAG_NAME || env.BRANCH_NAME ==~ 'sprint-.*') {
                    step([$class: 'JiraIssueUpdater', issueSelector: [$class: 'DefaultIssueSelector'], scm: scm])
                }
            }
        }
    }
}

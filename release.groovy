/**
 * (C) Copyright 2020-2021 Nuxeo (http://nuxeo.com/).
 * This is unpublished proprietary source code of Nuxeo SA. All rights reserved.
 * Notice of copyright on this source code does not indicate publication.
 *
 * Contributors:
 *      jcarsique
 */

void setGitHubBuildStatus(String context) {
    step([
            $class       : 'GitHubCommitStatusSetter',
            reposSource  : [$class: 'ManuallyEnteredRepositorySource', url: 'https://github.com/nuxeo/nuxeo-vision'],
            contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: context],
            errorHandlers: [[$class: 'ShallowAnyErrorHandler']]
    ])
}

pipeline {
    agent {
        label "jenkins-ai-nuxeo11"
    }
    parameters {
        string(name: 'BRANCH', defaultValue: 'master', description: 'Branch to release from.')
        string(name: 'VERSION', description: '''The version to release.
Leave it unset to use the version of the Maven POM.
The version must match "X.Y.Z[-PRERELEASE][+BUILD]"''')
        choice(name: 'INCREMENT', choices: ['patch', 'minor', 'major', 'release'], description: '''Semantic versioning increment.
The "release" increment mode removes any PRERELEASE or BUILD parts (see VERSION).''')
        booleanParam(name: 'DRY_RUN', defaultValue: true, description: 'Dry run')
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '3'))
        timeout(time: 1, unit: 'HOURS')
    }
    stages {
        stage('Release') {
            steps {
                setGitHubBuildStatus('release')
                container('platform11') {
                    withEnv(["BRANCH=${params.BRANCH}", "VERSION=${params.VERSION}", "INCREMENT=${params.INCREMENT}",
                             "DRY_RUN=${params.DRY_RUN}"]) {
                        script {
                            sh './tools/release.sh'
                            sh 'chmod +r VERSION charts/*/templates/release.yaml || true'
                            if ("$DRY_RUN" != 'true') {
                                def releaseProps = readProperties file: 'release.properties'
                                def jobName = '/nuxeo/nuxeo-vision/v' + releaseProps['RELEASE_VERSION']
                                if (!Jenkins.instance.getItemByFullName(jobName)) {
                                    build job: '/nuxeo/nuxeo-vision/', propagate: false, wait: true
                                }
                                build job: jobName, propagate: false, wait: false
                            }
                        }
                    }
                }
            }
            post {
                always {
                    setGitHubBuildStatus('release')
                    archiveArtifacts artifacts: 'release.properties, charts/*/templates/release.yaml', allowEmptyArchive: true
                }
            }
        }
    }
}

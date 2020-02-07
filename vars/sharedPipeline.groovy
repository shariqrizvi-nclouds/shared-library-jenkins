def isStartedByTimer() {
	def buildCauses = currentBuild.getBuildCauses()


	boolean isStartedByTimer = false
	for (buildCause in buildCauses) {
		if ("${buildCause}".contains("hudson.triggers.SCMTrigger")) {
			isStartedByTimer = true
		}
	}
	return isStartedByTimer
}

def sendSlackNotification(){
    message="Layer2 Testing Jenkins Job"

    header="{ 'type': 'section', 'text': { 'type': 'mrkdwn', 'text': '$message' } }"
    divider="{ 'type': 'divider' }"

    data="{ 'blocks': [ $header, $divider ] }"

    echo "$data"

    sh "curl -X POST -H 'Content-type: application/json' --data '{\"text\":\"Jenkins Job Notification Layer2 Test\"}' https://hooks.slack.com/services/token_id"
}

def call(Map pipelineParams){
    String cron_string = "* * * * *"
    def scm = "${isStartedByTimer()}"
    commit = ""
    pipeline {

        agent any
        parameters {
            string(name: 'GIT_REV', defaultValue: 'latest', description: 'The git commit you want to build (Keep \'latest\' value for latest commit)')
            choice(name: 'OPTION', choices: ['prod-deploy','dev-deploy', 'test', 'build'])
        }
        options {
            disableConcurrentBuilds()
        }
        triggers {
            pollSCM(cron_string)
        }

        stages {

            stage('Linting'){
                when {
                    expression {
                            params.GIT_REV == "latest"
                    }
                }
                steps {
                    container('docker') {
                        script {
                            commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                            echo 'Linting Docker image with Hadolint...'
                            sh 'docker run --rm -i hadolint/hadolint hadolint - < Dockerfile'
                        }
                    }
                }
            }

            stage('Build') {
                when {
                    expression {
                            params.GIT_REV == "latest"
                    }
                }
                steps {
                    container('docker') {	
                        script {	
                            sh "docker build -t ${pipelineParams.ECR_REPO_NAME} --network=host ."
                            sh "\$(aws ecr get-login --no-include-email --region ${pipelineParams.AWS_REGION})"	
                            sh "docker tag ${pipelineParams.ECR_REPO_NAME} ${pipelineParams.ECR_REPO}:${commit}"
                            sh "docker tag ${pipelineParams.ECR_REPO_NAME} ${pipelineParams.ECR_REPO}:latest"
                            sh "docker push ${pipelineParams.ECR_REPO}:${commit}"
                            sh "docker push ${pipelineParams.ECR_REPO}:latest"
                            sh 'echo "Stage push done"'
                        }
                    }
                }
            }

            stage('ECR Vulnerability Scanner') {
                when {
                    expression {
                            params.GIT_REV == "latest"
                    }
                }
                steps {
                    container('docker') {	
                        script {
                            echo "Startin image vulneratbility scan on ECR"
                            sh "aws ecr start-image-scan --repository-name ${pipelineParams.ECR_REPO_NAME} --image-id imageTag=${commit} --region ${pipelineParams.AWS_REGION}|| true"
                            sh "aws ecr wait image-scan-complete --repository-name ${pipelineParams.ECR_REPO_NAME} --image-id imageTag=${commit} --region ${pipelineParams.AWS_REGION}"
                            sh "aws ecr describe-image-scan-findings --repository-name ${pipelineParams.ECR_REPO_NAME} --image-id imageTag=${commit} --region ${pipelineParams.AWS_REGION}"
                        }
                    }
                }
            }

            stage('Test') {
                when {
                    allOf {
                        expression {
                            params.GIT_REV == "latest"
                        }
                        anyOf {
                            expression {
                                params.OPTION == "test"
                            }
                            expression {
                                params.OPTION == "dev-deploy"
                            }
                            expression {
                                params.OPTION == "prod-deploy"
                            }
                        }
                    }
                }
                steps {
                    sh 'echo "Stage test done"'
                }
            }

            stage('Dev Deployment') {
                when {
                    anyOf {
                        expression {
                            params.OPTION == "dev-deploy"
                        }
                        expression {
                            params.OPTION == "prod-deploy"
                        }
                    }
                }
                steps {
                    // error('failed')
                    container('docker') {	
                        script {	
                            sh "aws eks update-kubeconfig --name ${pipelineParams.EKS_DEV_CLUSTER} --region ${pipelineParams.AWS_REGION}"
                            if (params.GIT_REV == "latest") {	
                                sh "kubectl set image deployment/${pipelineParams.DEPLOYMENT_NAME} ${pipelineParams.DEPLOYMENT_NAME}=${pipelineParams.ECR_REPO}:${commit} --record"
                            }
                            else {
                                sh "kubectl set image deployment/${pipelineParams.DEPLOYMENT_NAME} ${pipelineParams.DEPLOYMENT_NAME}=${pipelineParams.ECR_REPO}:${params.GIT_REV} --record"
                            }
                            sh 'echo "Stage deploy done"'	
                        }	
                    }
                }

                post {
                    success {
                        echo "post deploy: success"
                    }
                }
                
            }

            stage('Live Tests') {
                when {
                    anyOf {
                        expression {
                            params.OPTION == "dev-deploy"
                        }
                        expression {
                            params.OPTION == "prod-deploy"
                        }
                    }
                }
                steps {
                    sh 'echo "Live test done"'
                }
            }

            stage('Approval'){
                when {
                    expression {
                        params.OPTION == "prod-deploy"
                    }
                }
                steps {
                    script{
                        def IsTimeout = false
                        def userInput = true
                        try {
                            timeout(time: 300, unit: 'SECONDS') {
                                userInput = input(
                                id: 'userInput', message: 'Deploy to Prod?', parameters: [
                                    [$class: 'BooleanParameterDefinition', defaultValue: true, description: 'Deploy to Production?', name: 'PROD']
                                ]);
                            }
                        } 
                        catch(err) { // timeout reached or input false
                            userInput = false
                        }

                        if (userInput == true) {
                            stage('Prod Deployment') {

                                container('docker') {
                                    script {
                                        sh "echo deploying to prod..."
                                        sh "aws eks update-kubeconfig --name ${pipelineParams.EKS_PROD_CLUSTER} --region ${pipelineParams.AWS_REGION}"
                                        if (params.GIT_REV == "latest") {	
                                            sh "kubectl set image deployment/${pipelineParams.DEPLOYMENT_NAME} ${pipelineParams.DEPLOYMENT_NAME}=${pipelineParams.ECR_REPO}:${commit} --record"
                                        }
                                        else {
                                            sh "kubectl set image deployment/${pipelineParams.DEPLOYMENT_NAME} ${pipelineParams.DEPLOYMENT_NAME}=${pipelineParams.ECR_REPO}:${params.GIT_REV} --record"
                                        }
                                    }
                                }
                                //sendSlackNotification()
                            }
                        }
                    }
                }
            }
            
        }
    }

}

def trigger(){
	script {
                echo "${isStartedByTimer()}"
        }
}

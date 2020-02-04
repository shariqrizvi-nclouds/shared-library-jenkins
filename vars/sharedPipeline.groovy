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


def call(){
    String cron_string = "* * * * *"
    def scm = "${isStartedByTimer()}"
    commit = ""

    pipeline {
        agent any
        parameters {
            string(name: 'EKS_PROD_CLUSTER', description: 'The name of the eks prod cluster')
            string(name: 'EKS_DEV_CLUSTER', description: 'The name of the eks dev cluster')
            string(name: 'AWS_REGION', description: 'region to be deployed')
            string(name: 'ECR_REPO', description: 'Full ECR repo base image uri')
            string(name: 'ECR_REPO_NAME', description: 'ECR repo name')
            string(name: 'DEPLOYMENT_NAME', description: 'Deployment name in Kubernetes Deployment File')
            choice(name: 'OPTION', choices: ['build', 'test', 'dev-deploy', 'prod-deploy'])
        }


        options {
            disableConcurrentBuilds()
        }
        triggers {
            pollSCM(cron_string)
        }

        stages {

            stage('Checkout') {
                steps {
                    script {
                        commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    }
                }
            }

            stage('Linting'){
                steps {
                    script {
                        echo 'Linting Docker image with Hadolint...'
                        // sh 'docker run --rm -i hadolint/hadolint hadolint - < Dockerfile'
                    }
                }
            }

            stage('Build') {
                when {
                    anyOf {
                        expression {
                            "${scm}" == "true"
                        }
                        expression {
                            params.OPTION == "deploy"
                        }
                    }
                }
                steps {
                    container('docker') {	
                        script {	
                            sh "docker build -t ${ECR_REPO_NAME} --network=host ."
                            sh "\$(aws ecr get-login --no-include-email --region ${AWS_REGION})"	
                            sh "docker tag ${ECR_REPO_NAME} ${ECR_REPO}:${commit}"
                            sh "docker tag ${ECR_REPO_NAME} ${ECR_REPO}:latest"
                            sh "docker push ${ECR_REPO}:${commit}"
                            sh "docker push ${ECR_REPO}:latest"
                            sh 'echo "Stage push done"'
                        }
                    }
                }
            }

            stage('Vulnerability Scanner') {
                steps {
                    container('docker') {	
                        script {
                            echo "Startin image vulneratbility scan on ECR"
                            sh "aws ecr start-image-scan --repository-name ${ECR_REPO_NAME} --image-id imageTag=${commit} --region ${AWS_REGION}|| true"
                            sh "aws ecr wait image-scan-complete --repository-name ${ECR_REPO_NAME} --image-id imageTag=${commit} --region ${AWS_REGION}"
                            sh "aws ecr describe-image-scan-findings --repository-name ${ECR_REPO_NAME} --image-id imageTag=${commit} --region ${AWS_REGION}"
                        }
                    }
                }
            }

            stage('Test') {
                when {
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
                            sh "aws eks update-kubeconfig --name ${EKS_DEV_CLUSTER} --region ${AWS_REGION}"	
                            sh "kubectl set image deployment/${DEPLOYMENT_NAME} ${DEPLOYMENT_NAME}=${ECR_REPO}:${commit} --record"
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
                                        sh "aws eks update-kubeconfig --name ${EKS_PROD_CLUSTER} --region ${AWS_REGION}"
                                        sh "kubectl set image deployment/${DEPLOYMENT_NAME} ${DEPLOYMENT_NAME}=${ECR_REPO}:${commit} --record"
                                        
                                        
                                        message="Layer2 Testing Jenkins Job"

                                        header="{ 'type': 'section', 'text': { 'type': 'mrkdwn', 'text': '$message' } }"
                                        divider="{ 'type': 'divider' }"

                                        data="{ 'blocks': [ $header, $divider ] }"

                                        echo "$data"

                                        sh "curl -X POST -H 'Content-type: application/json' --data 'Layer2 Testing Jenkins Job' https://hooks.slack.com/services/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"
                                    }
                                }
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

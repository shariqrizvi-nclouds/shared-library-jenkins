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
            string(name: 'EKS_PROD_CLUSTER', defaultValue: 'nclouds-eks-prod', description: 'The name of the eks prod cluster')
            string(name: 'EKS_DEV_CLUSTER', defaultValue: 'nclouds-eks-dev', description: 'The name of the eks cluster')
            string(name: 'AWS_REGION', defaultValue: 'us-east-1')
            string(name: 'ECR_REPO', defaultValue: '695292474035.dkr.ecr.us-east-1.amazonaws.com/nclouds-eks-nodejs')
            string(name: 'DEPLOYMENT_NAME', defaultValue: 'ecsdemo-nodejs')
            string(name: 'DOCKER_TAG_NAME', defaultValue: 'nclouds-eks-nodejs')
            string(name: 'OPTION', defaultValue: 'deploy')
        }


        options {
            disableConcurrentBuilds()
        }
        triggers {
            pollSCM(cron_string)
        }

        stages {

            stage('Checkout') {
                when{
                    not {
                        expression {
                            params.OPTION == "re-deploy"
                        }
                    }
                }
                
                steps {
                    script {
                        git url: 'https://github.com/nclouds/demo-nodejs.git', branch: 'master'
                        commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    }
                }
            }

            stage('build') {
                when {
                    allOf {
                        not {
                            expression {
                                params.OPTION == "re-deploy"
                            }
                        }
                        expression {
                            params.GIT_REV == ""
                        }
                    }
                }
                steps {
	            sh "docker build -t ${DOCKER_TAG_NAME} --network=host ."
                sh 'echo "Stage build done"'
                }
            }


            stage('test') {
                when {
                    allOf {
                        not {
                            expression {
                                params.OPTION == "re-deploy"
                            }
                        }
                    
                        anyOf {
                            expression {
                                "${scm}" == "true"
                            }
                            not {
                                allOf {
                                    expression {
                                        params.GIT_REV != ""
                                    }
                                    expression {
                                        params.OPTION == "deploy"
                                    }
                                }
                            }


                        }
                    }

                }
                steps {
                    sh 'echo "Stage test done"'
                }
            }

	        stage('Vulnerability Scanner') {
                steps {
                    sh 'echo "Vulnerability Scanning done"'
                }
            }

            stage('push') {
                when {
                    allOf {
                        not {
                            expression {
                                params.OPTION == "re-deploy"
                            }
                        }
                    
                        anyOf {
                            expression {
                                "${scm}" == "true"
                            }
                            expression {
                                params.OPTION == "deploy"
                            }
                        }
                    }


                }

                steps {
                    container('docker') {	
                        script {	
                            sh "\$(aws ecr get-login --no-include-email --region ${AWS_REGION})"	
                            sh "docker tag ${DOCKER_TAG_NAME} ${ECR_REPO}:${commit}"
                            sh "docker push ${ECR_REPO}:${commit}"
                            sh 'echo "Stage push done"'
                        }
                    }
                }
            }
            


            stage('add-artifacts'){
                when {
                    expression {
                        params.OPTION == "deploy"
                    }
                }
                steps{
                    script {
                        sh 'echo "Stage re-deploy done"'

                    }
                    
                }
            }



            stage('deploy-dev') {
                when {
                    allOf {
                        not {
                            expression {
                                params.OPTION == "re-deploy"
                            }
                        }
                    
                        anyOf {
                            expression {
                                "${scm}" == "true"
                            }
                            expression {
                                params.OPTION == "deploy"
                            }
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

            stage('re-deploy-dev'){
                when {
                    expression {
                        params.OPTION == "re-deploy"
                    }
                }
                steps{
                    script {
                        
                        String jsonString = """{"spec":{"template":{"metadata":{"labels":{"date":"${currentBuild.startTimeInMillis}"}}}}}"""
                        writeFile(file:'message2.json', text: jsonString)
                        sh 'cat message2.json'
                        sh 'echo "Stage re-deploy done"'

                    }
                    
                }
            }

            stage('Check for deployment'){
                when {
                    allOf {
                        not {
                            expression {
                                params.OPTION == "re-deploy"
                            }
                        }
                    
                        anyOf {
                            expression {
                                "${scm}" == "true"
                            }
                            expression {
                                params.OPTION == "deploy"
                            }
                        }
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

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

    pipeline {
        agent any
        parameters {
            string(name: 'GIT_REV', defaultValue: '', description: 'The git commit you want to build')
            string(name: 'EKS_CLUSTER', defaultValue: 'qa_nclouds', description: 'The name of the eks cluster')
            string(name: 'AWS_REGION', defaultValue: 'us-west-2')
            choice(name: 'OPTION', choices: ['test', 'build', 'add-artifacts', 'deploy', 're-deploy', 'new1'])
        }


        options {
            disableConcurrentBuilds()
        }
//        triggers {
//            pollSCM(cron_string)
//        }

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
                        echo "Hello Wordld"
                        echo "${scm}"
                        echo "${EKS_CLUSTER}"
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

                            allOf {
                                expression {
                                    params.GIT_REV == ""
                                }
                                anyOf {
                                    expression {
                                        params.OPTION == "deploy"
                                    }
                                    expression {
                                        params.OPTION == "build"
                                    }
                                }
                            }

                        }
                    }
                }

                steps {
                    sh 'echo "Stage push done"'
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



            stage('deploy') {
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
                    sh 'echo "Stage deploy done"'
                    // error('failed')
                }

                post {
                    success {
                        echo "post deploy: success"
                    }
                }
                
            }

            stage('re-deploy'){
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

            
        }
    }

}

def trigger(){
	script {
                echo "${isStartedByTimer()}"
        }
}

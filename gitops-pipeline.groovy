def createBooleanParameter(String desc, String value){
  return [$class: 'BooleanParameterDefinition', defaultValue: false, description: desc, name: value]
}

def userInput = input(
  id: 'userInput', message: 'Déployer sur quelles usines cibles?', parameters: [
    createBooleanParameter('SI Central','ocp42.ocp42'),
    createBooleanParameter('Usine 1','ocp42.dc')
  ])


pipeline {
  agent {
    label 'master'
  }

  stages {
    stage('Deploy to factories') {
      steps {

          script {
            //def usines = ["ocp42.ocp42", "ocp42.dc"];  

            def output = "";
            def usines = [];
            userInput?.findAll{ it.value }?.each {
              //println it.key.toString()
              output += it.key.toString() + " ";
              println output
              usines.add(it.key.toString());
            }

            ansibleTower(
              towerServer: 'tower',
              templateType: 'job',
              jobTemplate: 'deploy-edge-factory-apps',
              importTowerLogs: true,
              inventory: '',
              removeColor: false,
              verbose: false,
              credential: '',  
              limit: '',
              extraVars: """---
                var_cluster_list:  ${usines} 
              """
            )
          }



        script {
            env.RELEASE_SCOPE = input message: 'User input required', ok: 'Release!',
                    parameters: [choice(name: 'RELEASE_SCOPE', choices: 'patch\nminor\nmajor', description: 'What is the release scope?'),
                    booleanParam(name: 'Param2', defaultValue: false, description: 'Boolean parameter 2'),
                    booleanParam(name: 'Param3', defaultValue: false, description: 'Boolean parameter 3')]
        }
        echo "${env.RELEASE_SCOPE}"
      }
    }
    stage('Deploy DEV') {
      steps {
        script {
          openshift.withCluster() {
            openshift.withProject(env.DEV_PROJECT) {
              openshift.selector("dc", "tasks").rollout().latest();
            }
          }
        }
      }
    }
    stage('Promote to STAGE?') {
      agent {
        label 'skopeo'
      }
      steps {
        timeout(time:15, unit:'MINUTES') {
            input message: "Promote to STAGE?", ok: "Promote"
        }

        script {
          openshift.withCluster() {
            if (env.ENABLE_QUAY.toBoolean()) {
              withCredentials([usernamePassword(credentialsId: "${openshift.project()}-quay-cicd-secret", usernameVariable: "QUAY_USER", passwordVariable: "QUAY_PWD")]) {
                sh "skopeo copy docker://quay.io/jchraibi/tasks-app:latest docker://quay.io/jchraibi/tasks-app:stage --src-creds \"$QUAY_USER:$QUAY_PWD\" --dest-creds \"$QUAY_USER:$QUAY_PWD\" --src-tls-verify=false --dest-tls-verify=false"
              }
            } else {
              openshift.tag("${env.DEV_PROJECT}/tasks:latest", "${env.STAGE_PROJECT}/tasks:stage")
            }
          }
        }
      }
    }
    stage('Deploy STAGE') {
      steps {
        script {
          openshift.withCluster() {
            openshift.withProject(env.STAGE_PROJECT) {
              openshift.selector("dc", "tasks").rollout().latest();
            }
          }
        }
      }
    }
  }
}

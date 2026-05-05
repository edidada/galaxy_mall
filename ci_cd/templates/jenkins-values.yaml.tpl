controller:
  image:
    registry: "docker.io"
    repository: "jenkins/jenkins"
    tag: "2.541.1-jdk21"
  admin:
    username: "__JENKINS_ADMIN_USER__"
    password: "__JENKINS_ADMIN_PASSWORD__"
  jenkinsUrl: "http://__JENKINS_HOST__"
  jenkinsAdminEmail: "__GITLAB_ROOT_EMAIL__"
  serviceType: ClusterIP
  ingress:
    enabled: true
    ingressClassName: traefik
    hostName: "__JENKINS_HOST__"
  installLatestPlugins: false
  installLatestSpecifiedPlugins: false
  containerEnv:
    - name: GITLAB_ROOT_USERNAME
      value: "__GITLAB_ROOT_USERNAME__"
    - name: GITLAB_ROOT_TOKEN
      value: "__GITLAB_ROOT_TOKEN__"
    - name: GITLAB_PROJECT_HTTP_URL
      value: "__GITLAB_PROJECT_HTTP_URL__"
    - name: GITLAB_REGISTRY_HOST
      value: "__GITLAB_REGISTRY_HOST__"
    - name: JENKINS_WEBHOOK_TOKEN
      value: "__JENKINS_WEBHOOK_TOKEN__"
    - name: GITLAB_DEFAULT_BRANCH
      value: "__GITLAB_DEFAULT_BRANCH__"
  installPlugins:
    - kubernetes:4392.v19cea_fdb_5913
    - workflow-aggregator:608.v67378e9d3db_1
    - git:5.7.0
    - job-dsl:1.93
    - configuration-as-code:2074.va_57f83f7a_10b_
    - generic-webhook-trigger:2.4.1
    - credentials:1447.v4cb_b_539b_5321
    - credentials-binding:687.v619cb_15e923f
    - plain-credentials:199.v9f8e1f741799
  JCasC:
    defaultConfig: true
    configScripts:
      system-message: |
        jenkins:
          systemMessage: "Galaxy Mall Jenkins, managed by JCasC.\n"
          globalNodeProperties:
            - envVars:
                env:
                  - key: K3S_NODE_IP
                    value: "__K3S_NODE_IP__"
                  - key: BASE_DOMAIN
                    value: "__BASE_DOMAIN__"
                  - key: APP_NAMESPACE
                    value: "__APP_NAMESPACE__"
                  - key: MIDDLEWARE_NAMESPACE
                    value: "__MIDDLEWARE_NAMESPACE__"
                  - key: APP_IMAGE_REGISTRY
                    value: "__APP_IMAGE_REGISTRY__"
                  - key: APP_IMAGE_REPOSITORY
                    value: "__APP_IMAGE_REPOSITORY__"
                  - key: MALL_HOST
                    value: "__MALL_HOST__"
                  - key: REDIS_PASSWORD
                    value: "__REDIS_PASSWORD__"
                  - key: MYSQL_APP_PASSWORD
                    value: "__MYSQL_APP_PASSWORD__"
      credentials: |
        credentials:
          system:
            domainCredentials:
              - credentials:
                  - usernamePassword:
                      id: "gitlab-http-credentials"
                      scope: GLOBAL
                      username: "${GITLAB_ROOT_USERNAME}"
                      password: "${GITLAB_ROOT_TOKEN}"
                      description: "GitLab HTTP checkout and API token"
                  - usernamePassword:
                      id: "gitlab-registry-credentials"
                      scope: GLOBAL
                      username: "${GITLAB_ROOT_USERNAME}"
                      password: "${GITLAB_ROOT_TOKEN}"
                      description: "GitLab registry push credential"
      jobs: |
        jobs:
          - script: >
              pipelineJob('galaxy-mall-delivery') {
                description('Build, push and deploy Galaxy Mall to k3s')
                definition {
                  cpsScm {
                    scm {
                      git {
                        remote {
                          url("${GITLAB_PROJECT_HTTP_URL}")
                          credentials('gitlab-http-credentials')
                        }
                        branch("*/${GITLAB_DEFAULT_BRANCH}")
                      }
                    }
                    scriptPath('ci_cd/pipeline/Jenkinsfile')
                  }
                }
                triggers {
                  genericTrigger {
                    genericVariables {
                      genericVariable {
                        key('ref')
                        value('$.ref')
                        expressionType('JSONPath')
                      }
                    }
                    token("${JENKINS_WEBHOOK_TOKEN}")
                    causeString('Triggered by GitLab push')
                    printContributedVariables(true)
                    printPostContent(false)
                    regexpFilterText('$ref')
                    regexpFilterExpression('refs/heads/${GITLAB_DEFAULT_BRANCH}')
                  }
                }
              }
agent:
  enabled: true
  image:
    registry: "docker.io"
    repository: "jenkins/inbound-agent"
    tag: "3345.v03dee9b_f88fc-6"
  workingDir: "/home/jenkins/agent"
rbac:
  create: true
persistence:
  enabled: true
  size: 30Gi

def call(JIRA_ISSUE_KEY, DEFAULT_BILLING_API, APP_PREFIX, awsProfileName, repoName, buildCallback) {

  node {

    def IS_MATCHED
    def PULL_REQUEST_ID
    def SOURCE_COMMIT
    def STAGE_URL;
    def APP_ID;


    def pullRequestData;

    stage('Matching pull request with Jira ticket') {

      currentBuild.displayName = "Processing $JIRA_ISSUE_KEY"

      pullRequestData = getMatchPullRequestsByJiraIssueKey(JIRA_ISSUE_KEY, ALLOWED_DESTINATION, awsProfileName, repoName)

      IS_MATCHED = pullRequestData.result
      if (pullRequestData.result) {
        PULL_REQUEST_ID = pullRequestData.PULL_REQUEST_ID
        SOURCE_COMMIT = pullRequestData.SOURCE_COMMIT
      } else {
        currentBuild.description = "No matching pull requests found"
      }

    }

    if (IS_MATCHED) {

      stage('Build server') {

        if (APP_PREFIX) {
          APP_ID = "PR-" + pullRequestData.PULL_REQUEST_ID + "-" + APP_PREFIX
        } else {
          APP_ID = "PR-" + pullRequestData.PULL_REQUEST_ID
        }

        STAGE_URL = "http://${APP_ID}.jenkins.playwing.com"

        def appConfigFile = getAppConfigFilePath(APP_ID)
        def appDirectory = getAppWorkspacePath(APP_ID)
        def buildVariables = resolveBuildVariables(appConfigFile, DEFAULT_BILLING_API)

        buildCallback(APP_ID, SOURCE_COMMIT, appDirectory, buildVariables);

        setupNginxVirtualHost(APP_ID);

      }

      stage('Send notifications') {

        def response = jiraGetComments idOrKey: JIRA_ISSUE_KEY

        def pattern = /.*Rebuild this.*/
        def lastCommentAuthor = null
        for (comment in response.data.comments) {

          def expression = (comment.body =~ pattern)
          if (expression.find()) {
            lastCommentAuthor = comment.author.emailAddress
          }
        }

        if (lastCommentAuthor) {
          withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {
            def profiles = slackGetUserList(TOKEN)

            if (profiles.containsKey(lastCommentAuthor)) {
              def item = profiles[lastCommentAuthor]
              slackSend color: 'good', message: "Rebuild of ${JIRA_ISSUE_KEY} was successful.\n$STAGE_URL", channel: "@${item.name}"
            } else {
              println "Cannot find slack account for $lastCommentAuthor"
            }
          }
        } else {
          println "Cannot find comment that matches pattern `$pattern`. Probably misconfiguration issue, or comment is already deleted"
        }

        jiraComment body: "Rebuild was successful.\n$STAGE_URL", issueKey: JIRA_ISSUE_KEY

      }
    }
  }
}
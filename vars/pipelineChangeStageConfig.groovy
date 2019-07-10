def call(awsProfileName, APP_ID, BILLING_API_HOST, DB_UPDATE_TYPE, buildCallback) {
  node {

    def buildVariables

    def commitHash

    currentBuild.displayName = "Changing configuration for $APP_ID"

    stage('Update build configuration') {

      def pattern = /PR-(\d+)/
      def expression = (APP_ID =~ pattern)
      if (expression.find()) {
        prId = expression.group(1).toInteger();
        println "Parsed $prId from APP_ID"
      } else {
        error('Cannot parse pull request ID from $APP_ID')
      }
      expression = null

      def PRInfo = executeAWSCliCommand("codecommit", "get-pull-request", ["pull-request-id": prId, "profile-name": awsProfileName])

      commitHash = PRInfo.pullRequest.pullRequestTargets[0].sourceCommit;

      def appConfigFile = getAppConfigFilePath(APP_ID)

      println "Applying new configuration for $APP_ID"
      buildVariables = [
          BILLING_API_HOST: BILLING_API_HOST,
          DB_UPDATE_TYPE  : DB_UPDATE_TYPE
      ]
      writeToExternalJsonFile(appConfigFile, buildVariables)

    }
    stage('Build') {

      def appDirectory = getAppWorkspacePath(APP_ID)

      def stageBuild = buildCallback(APP_ID, commitHash, appDirectory, buildVariables);

    }
  }
}

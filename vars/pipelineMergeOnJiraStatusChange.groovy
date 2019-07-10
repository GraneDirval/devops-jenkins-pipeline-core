def call(awsProfileName, gitRepo, repoName, List primaryReviewerList, List secondaryReviewerList, prLinkCallback) {

  node {
    currentBuild.displayName = "Issue $JIRA_ISSUE_KEY was updated"

    def builds = getJenkinsBuilds(JOB_NAME, true)
    for (build in builds) {
      if (build != currentBuild.getRawBuild()) {
        def parameters = getJenkinsBuildParameters(build);
        if (JIRA_ISSUE_KEY == parameters['JIRA_ISSUE_KEY']) {
          println "Issue $JIRA_ISSUE_KEY is already in process";
          currentBuild.description = "Issue is already in process - aborting.<br>";
          currentBuild.result = 'ABORTED'
          return;
        }
      }
    }
    builds = null; // SerializationException fix

    def IS_MATCHED;
    def PULL_REQUEST_ID;
    def SOURCE_COMMIT;
    def SOURCE_REFERENCE;
    def IS_PRE_MERGE_COMMIT_CREATED;



    stage('Finding of proper pull request') {
      script {
        def pullRequestData = getMatchPullRequestsByJiraIssueKey(
            JIRA_ISSUE_KEY,
            ALLOWED_DESTINATION,
            awsProfileName,
            repoName
        )

        IS_MATCHED = pullRequestData.result

        print pullRequestData;

        if (pullRequestData.result == true) {
          PULL_REQUEST_ID = pullRequestData.PULL_REQUEST_ID
          SOURCE_COMMIT = pullRequestData.SOURCE_COMMIT
          SOURCE_REFERENCE = pullRequestData.SOURCE_REFERENCE
        }
      }
    }

    if (!IS_MATCHED) {
      println "No matching pull requests found for issue $JIRA_ISSUE_KEY"
      currentBuild.description = "No matching pull requests found - aborting.<br>"
      currentBuild.result = 'ABORTED'
      return;
    }


    def SLACK_USER_NAME;
    withCredentials([string(credentialsId: 'jenkins-bot-oauth-key', variable: 'TOKEN')]) {
      def profiles = slackGetUserList(TOKEN)
      println JIRA_ISSUE_ASSIGNEE_EMAIL
      if (profiles.containsKey(JIRA_ISSUE_ASSIGNEE_EMAIL)) {
        def item = profiles[JIRA_ISSUE_ASSIGNEE_EMAIL]
        SLACK_USER_NAME = item.name
      }
    }

    currentBuild.description = "Processing PR-$PULL_REQUEST_ID.<br>"

    Boolean IS_PRE_MERGE_SUCCESSFUL = false;

    stage('Pre-review merging') {

      sh 'git init'
      sh 'git config credential.helper cache'

      checkout changelog: true, poll: false, scm: [
          $class                           : 'GitSCM',
          branches                         : [[name: SOURCE_REFERENCE]],
          doGenerateSubmoduleConfigurations: false,
          extensions                       : [
              [$class: 'CleanBeforeCheckout'],
              [$class: 'ChangelogToBranch', options: [compareRemote: 'origin', compareTarget: 'stage']]

          ],
          submoduleCfg                     : [],
          userRemoteConfigs                : [gitRepo]
      ]

      try {
        sh "git merge origin/stage --no-commit";
      } catch (Exception e) {
        jiraComment body: "Cannot merge PR-${PULL_REQUEST_ID}.\nPlease resolve branch conflicts.", issueKey: JIRA_ISSUE_KEY
        jiraTransitionIssueByName(JIRA_ISSUE_KEY, "Merge Failed")

        sendMessageToSlack(
            "Cannot merge PR-${PULL_REQUEST_ID} (${JIRA_ISSUE_KEY}).\nPlease resolve branch conflicts.",
            SLACK_USER_NAME,
            "FF0000"
        )

        IS_PRE_MERGE_SUCCESSFUL = false;
        return;
      }

      try {
        sh "git commit --author 'Jenkins-CI <jenkins@playwing.com>' -m 'Merge `PR-$PULL_REQUEST_ID` ($JIRA_ISSUE_KEY) into `stage`'"
        IS_PRE_MERGE_COMMIT_CREATED = true;
        println "Pre-review merge was successful";

      } catch (Exception e) {
        IS_PRE_MERGE_COMMIT_CREATED = false;
        println "Pre-review merge was skipped - nothing changed";

      }

      sh "git push origin HEAD:${SOURCE_REFERENCE}"
      IS_PRE_MERGE_SUCCESSFUL = true;
    }


    if (!IS_PRE_MERGE_SUCCESSFUL) {
      currentBuild.description += "Cannot merge because of conflicts.<br>"
      return
    }

    if (!isInReviewerList(SLACK_USER_NAME, primaryReviewerList)) {

      println "Trying to assign secondary reviewer who is not an author as Code Reviewer"
      List mergedReviewerList = primaryReviewerList;

      def secondaryReviewer = findSecondaryReviewer(
          secondaryReviewerList,
          SLACK_USER_NAME
      );

      if (secondaryReviewer) {

        mergedReviewerList << secondaryReviewer;
        jiraComment body: "${secondaryReviewer[0]} is selected as reviewer for this issue", issueKey: JIRA_ISSUE_KEY


      } else {
        println "No secondary reviewers are present"
      }

      String prLink = prLinkCallback(PULL_REQUEST_ID);

      try {
        stage('Waiting for Approval') {

          def prResolutionLink = "<${BUILD_URL}input|here>"

          sendMessageToReviewers(
              "$prLink (${JIRA_ISSUE_KEY}) waiting for your approval ${prResolutionLink}.",
              mergedReviewerList
          )

          sendMessageToSlack(
              "$prLink (${JIRA_ISSUE_KEY}) have no conflicts.\nWaiting for approval of reviewer.",
              SLACK_USER_NAME,
              "C0C0C0"
          )


          while (true) {

            def reviewerJenkinsString = makeJenkinsReviewersInputString(mergedReviewerList)

            try {
              timeout(time: 60, unit: 'MINUTES') {
                input message: "Is PR-$PULL_REQUEST_ID ok?",
                    submitter: reviewerJenkinsString,
                    id: 'code-review-input'
              }
              break;
            } catch (Exception err) {

              if (!isTimeoutException(err)) {
                throw err
              }

              def response = executeAWSCliCommand("codecommit", "get-pull-request", [
                  "pull-request-id": PULL_REQUEST_ID,
                  "profile"        : awsProfileName
              ])

              if (response.pullRequest.pullRequestStatus == 'CLOSED') {
                sendMessageToReviewers(
                    "$prLink (${JIRA_ISSUE_KEY}) is already closed. No need to review.\nProbably it was manually merged by someone or JIRA issue status change was triggered multiple times.",
                    mergedReviewerList
                )

                println "Pull Request is closed - aborting";
                currentBuild.result = 'ABORTED'
                break;
              } else {
                if (isWorkingTime()) {
                  println "Sending reminder"
                  sendMessageToReviewers(
                      "Reminder: ${prLink} (${JIRA_ISSUE_KEY}) is waiting for your approval ${prResolutionLink}.",
                      mergedReviewerList
                  )
                } else {
                  println "Not working time - is not neccessary to send reminder";
                }
              }
            }
          }
        }
      } catch (Exception e) {

        sendMessageToSlack(
            "Your $prLink (${JIRA_ISSUE_KEY}) is declined by reviewer.",
            SLACK_USER_NAME,
            "FF0000"
        )

        jiraTransitionIssueByName(JIRA_ISSUE_KEY, "Changes Requested")
        jiraComment body: "Changes has been requested for PR-${PULL_REQUEST_ID}.", issueKey: JIRA_ISSUE_KEY

        return
      }

      if (currentBuild.result == 'ABORTED') {
        return;
      }

    } else {
      println "Author is a Primary Reviewer - no need of review"
    }

    stage("Pushing to remote") {

      try {
        sh "git fetch origin stage"
        sh "git merge origin/stage --no-commit";
      } catch (Exception e) {

        jiraComment body: "Cannot merge PR-${PULL_REQUEST_ID}.\nPlease resolve branch conflicts.", issueKey: JIRA_ISSUE_KEY
        jiraTransitionIssueByName(JIRA_ISSUE_KEY, "Merge Failed")

        sendMessageToSlack(
            "Cannot merge PR-${PULL_REQUEST_ID} (${JIRA_ISSUE_KEY}).\nPlease resolve branch conflicts.",
            SLACK_USER_NAME,
            'FF0000'
        )

        return;
      }


      try {

        /*if (IS_PRE_MERGE_COMMIT_CREATED) {
        sh "git commit --amend --no-edit"
      } else {*/
        sh "git commit --author 'Jenkins-CI <jenkins@playwing.com>' -m 'Merge `PR-$PULL_REQUEST_ID` ($JIRA_ISSUE_KEY) into `stage`'"
        /*}*/

        println "Post-review merge was successful";
      } catch (Exception e) {

      }

      //sh "git push origin HEAD:stage"
      println "Pushed successfully";
      jiraTransitionIssueByName(JIRA_ISSUE_KEY, "Done")

      jiraComment body: "Successfully merged PR-${PULL_REQUEST_ID}.", issueKey: JIRA_ISSUE_KEY
      def slackComment = "Successfully merged PR-${PULL_REQUEST_ID} (${JIRA_ISSUE_KEY})."
      slackSend color: 'good', message: slackComment, channel: "@${SLACK_USER_NAME}"
      sh "git config --unset credential.helper"

    }

  }
}


def sendMessageToReviewers(message, List reviewers) {
  for (reviewer in reviewers) {
    def reviewerSlackName = reviewer[0];
    println "Sending message to reviewer $reviewerSlackName";
    slackSend color: 'C0C0C0',
        message: message,
        channel: "@${reviewerSlackName}"
  }
}

def sendMessageToSlack(message, slackUser, color) {
  slackSend color: color, message: message, channel: "@${slackUser}"
}

def isInReviewerList(slackUser, List reviewers) {

  for (reviewer in reviewers) {
    String reviewerSlackName = reviewer[0];

    if (reviewerSlackName == slackUser) {
      return true;
    }
  }
  return false;
}

def makeJenkinsReviewersInputString(List reviewers) {

  List names = [];

  for (reviewer in reviewers) {
    names << reviewer[1];
  }

  return names.join(',')

}

def findSecondaryReviewer(List secondaryReviewerList, ignoredUser) {

  def secondaryReviewer = null;

  List filteredSecondaryReviewers = secondaryReviewerList.findAll {
    it.value[0].toString() != ignoredUser
  }

  if (filteredSecondaryReviewers.size > 0) {
    Random randomValue = new Random();
    secondaryReviewer = filteredSecondaryReviewers[randomValue.nextInt(filteredSecondaryReviewers.size)];
  }

  return secondaryReviewer;

}
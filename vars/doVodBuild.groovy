def call(appId, commitHash, appDirectory, buildVariables) {
  def stageBuild = build job: 'ci-build-git-revision-vod', propagate: false, wait: true, parameters: [
      string(name: 'BILLING_API_HOST', value: buildVariables.BILLING_API_HOST),
      booleanParam(name: 'DROP_DB_ON_EACH_COMMIT', value: buildVariables.DROP_DB_ON_EACH_COMMIT),
      [$class: 'GitParameterValue', name: 'COMMIT_HASH', value: commitHash],
      string(name: 'APP_ID', value: appId),
      string(name: 'DIRECTORY_NAME', value: appDirectory),
      string(name: 'DB_UPDATE_TYPE', value: buildVariables.DB_UPDATE_TYPE),
  ];

  return stageBuild;
}
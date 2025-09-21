# jenkins-pipeline-shared

[deploy_api.groovy](vars/deploy_api.groovy)
```groovy
@Library('jenkins-pipeline-shared-gitlab@master') _

deploy_api([
    gitUrl: 'https://github.com/jiangchengyu998/photo-show.git',
    apiPort: '3000',
    branch: 'main',
    exe_node: "${exe_node}"
])

```

[add_rr.groovy](vars/add_rr.groovy)
```groovy
@Library('jenkins-pipeline-shared-gitlab@master') _

add_rr([
    RR: 'yy',
    exe_node: "${exe_node}"
])

```

# 命令速查

## crc

```bash
crc start | stop | status | delete
crc config set memory 16384 ; crc config set cpus 6 ; crc config view
crc console [--credentials]
eval $(crc oc-env)
```

## 登录与项目

```bash
oc login -u developer https://api.crc.testing:6443
oc login -u kubeadmin https://api.crc.testing:6443
oc whoami [-t]                # -t 打印 token
oc new-project creed-lab ; oc project creed-lab ; oc projects
```

## 部署与查看

```bash
oc new-app --image=<img> --name=<n>        # 快速部署
oc apply -f <dir|file> ; oc kustomize <dir>
oc get all -l app=<n> ; oc get pod -o wide
oc describe pod/<p> ; oc get events --sort-by=.lastTimestamp
oc explain deployment.spec.strategy        # 查字段含义，比搜索引擎准
oc get <kind> <name> -o yaml
```

## 配置

```bash
oc create configmap <n> --from-literal=K=V ; oc create secret generic <n> --from-literal=K=V
oc set env deploy/<n> --from=configmap/<n> ; oc set env deploy/<n> --list ; oc set env deploy/<n> K-
oc set probe deploy/<n> --readiness --get-url=http://:18097/actuator/health/readiness
oc set resources deploy/<n> --requests=cpu=100m,memory=384Mi --limits=memory=768Mi
```

## 构建与镜像

```bash
oc new-build --name <n> --strategy docker --binary
oc start-build <n> --from-dir=<ctx> --follow
oc get is,istag ; oc set triggers deploy/<n> --from-image=<is>:<tag> -c <container>
oc logs -f bc/<n>
```

## 发布

```bash
oc rollout status|history|undo|restart deploy/<n>
oc scale deploy/<n> --replicas=3
oc autoscale deploy/<n> --min=2 --max=4 --cpu-percent=70
```

## 网络与负载均衡

```bash
oc expose svc/<n>                                   # 创建 Route（不带 TLS）
oc create route edge <n> --service=<svc>            # edge TLS
oc get endpointslices -l kubernetes.io/service-name=<svc> -o wide
oc annotate route <n> haproxy.router.openshift.io/balance=roundrobin --overwrite
oc annotate route <n> haproxy.router.openshift.io/disable_cookies=true --overwrite
oc annotate route <n> haproxy.router.openshift.io/timeout=120s --overwrite
oc set route-backends <route> a=90 b=10 ; oc set route-backends <route>
oc -n openshift-ingress exec <router-pod> -- cat /var/lib/haproxy/conf/haproxy.config   # kubeadmin
```

## 排错

```bash
oc logs [-f] [--previous] deploy/<n>
oc debug deploy/<n>                 # 用同一个镜像起一个 shell
oc rsh deploy/<n> ; oc exec deploy/<n> -- <cmd>
oc port-forward svc/<n> 18097:8080
oc adm top pod                      # 需要开启集群监控
```

## Tekton

```bash
tkn pipeline list ; tkn pipeline start <p> -w name=ws,claimName=<pvc> --use-param-defaults --showlog
tkn pipelinerun list ; tkn pipelinerun logs -f --last
tkn task describe -n openshift-pipelines <task>   # 查看自带 Task 的参数名
```

## Argo CD

```bash
oc get applications -n openshift-gitops
oc get route openshift-gitops-server -n openshift-gitops
```

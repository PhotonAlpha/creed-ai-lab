# Tomcat Threads
sum by (name) (tomcat_threads_busy_threads{job="$job", instance="$instance"})
sum by (name) (tomcat_threads_current_threads{job="$job", instance="$instance"})
sum by (name) (tomcat_threads_config_max_threads{job="$job", instance="$instance"})


# Tomcat Connections
sum by (name) (tomcat_connections_current_connections{job="$job", instance="$instance"})
sum by (name) (tomcat_connections_keepalive_current_connections{job="$job", instance="$instance"})
sum by (name) (tomcat_connections_config_max_connections{job="$job", instance="$instance"})

# Servlet Throughput / Errors
sum by (name) (rate(tomcat_servlet_request_seconds_count{job="$job", instance="$instance"}[$__rate_interval]))
sum by (name) (rate(tomcat_servlet_error_total{job="$job", instance="$instance"}[$__rate_interval]))


# Servlet Max Request Time
max by (name) (tomcat_servlet_request_max_seconds{job="$job", instance="$instance"})

压测时把并发打满,同时观察三个数:如果 busy_threads 顶到 max_threads,current_connections 还在继续涨,而 keepalive_current_connections 基本不变——那么涨出来的部分基本就是在队列里等线程的请求。这能直观确认上面的关系。
所以如果目标是监控排队情况,current_connections 只能当间接信号,准确的还是得用之前说的方式把 executor 的 queue.size() 暴露出来。
current_connections − keepalive_connections − busy_threads ≈ 排队请求数


tomcat_connections_current_connections 里面有什么
它是 Connector 层面已被 Tomcat accept 的 socket 连接总数,大致由三部分组成:

正在被工作线程处理的连接 —— 对应 busy_threads
keepalive 空闲连接 —— 连接还在,但客户端当前没有发请求,对应 keepalive_current_connections
有请求到达、但还没分配到工作线程的连接 —— 这部分才是你关心的"排队中的请求"
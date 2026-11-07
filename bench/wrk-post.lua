wrk.method = "POST"
wrk.body = '{"model":"qwen-plus","messages":[{"role":"user","content":"wrk baseline"}],"stream":false}'
wrk.headers["Content-Type"] = "application/json"
wrk.headers["Authorization"] = "Bearer sk-demo"

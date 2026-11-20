"""记忆边界：State 保存工作状态和近期会话，checkpointer 保存可恢复现场。

工作状态使用 AgentState；执行现场由 runtime.checkpoints 管理。
完整对话、用户明确偏好与会话摘要由 Java 校验并保存到 MySQL。
conversation 模块只提炼候选数据，不直接连接商城数据库。
文档知识库由 rag 模块单独管理，不混入用户记忆，不使用随机向量。
"""

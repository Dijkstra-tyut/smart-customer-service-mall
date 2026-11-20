import request from '@/utils/request'

export const listCustomerTasks = (params) => request.get('/customer/tasks', { params })
export const getCustomerTask = (id) => request.get(`/customer/tasks/${encodeURIComponent(id)}`)
export const decideCustomerTask = (id, data) => request.post(`/customer/tasks/${encodeURIComponent(id)}/decision`, data, { preserveWhitespace: true })
export const retryCustomerTask = (id) => request.post(`/customer/tasks/${encodeURIComponent(id)}/retry`)

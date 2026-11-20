import request from '@/utils/request'

export const listCustomerSessions = () => request.get('/customer')

export const createCustomerSession = () => request.post('/customer')

export const deleteCustomerSession = (id) => request.delete(`/customer/${id}`)

export const listCustomerMessages = (sessionId) => request.get(`/customer/${sessionId}/messages`)

export const getCustomerRun = (sessionId) => request.get(`/customer/${encodeURIComponent(sessionId)}/run`)

export const cancelCustomerRun = (sessionId) => request.post(`/customer/${encodeURIComponent(sessionId)}/cancel`)

export const getCustomerLive = id => request.get(`/customer/${id}/live`)
export const handoffCustomer = id => request.post(`/customer/${id}/handoff`)
export const submitAfterSales = (id, data) => request.post(`/customer/${id}/after-sales`, data)
export const closeCustomerLive = id => request.post(`/customer/live/sessions/${id}/close`)

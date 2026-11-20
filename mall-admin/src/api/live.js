import request from '@/utils/request'

export const listLiveSessions = params => request.get('/customer/live/sessions', { params })
export const getLiveSession = id => request.get(`/customer/live/sessions/${id}`)
export const getLiveMessages = (id, afterId = 0) => request.get(`/customer/live/sessions/${id}/messages`, { params: { afterId, size: 100 } })
export const getLiveContext = id => request.get(`/customer/live/sessions/${id}/context`)
export const claimLiveSession = id => request.post(`/customer/live/sessions/${id}/claim`)
export const closeLiveSession = id => request.post(`/customer/live/sessions/${id}/close`)

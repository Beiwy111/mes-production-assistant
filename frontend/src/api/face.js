import request from '@/utils/request'

const options = { silent: true, timeout: 90000 }
export const faceCapabilities = () => request.get('/auth/face/capabilities', options)
export const previewFace = (data, signal) => request.post('/auth/face/preview', data, { ...options, timeout: 25000, signal })
export const createFaceChallenge = (username) => request.post('/auth/face/challenge', { username }, options)
export const faceLogin = (data) => request.post('/auth/face/login', data, options)
export const faceStatus = (userId) => request.get(userId ? `/auth/face/users/${userId}` : '/auth/face/me', options)
export const enrollFace = (data) => request.put('/auth/face/me', data, options)
export const testFaceMatch = (data) => request.post('/auth/face/me/test', data, options)
export const deleteFace = (password, userId) => request.delete(userId ? `/auth/face/users/${userId}` : '/auth/face/me', { ...options, data: { password } })

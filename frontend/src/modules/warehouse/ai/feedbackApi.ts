import { http, type ApiResponse } from '../../../shared/api/http'
import type { components } from '../../../generated/api-schema'

type FeedbackSnapshot = components['schemas']['FeedbackSnapshot']

export type FeedbackRating = NonNullable<FeedbackSnapshot['rating']> & ('HELPFUL' | 'NOT_HELPFUL')
export type FeedbackReason = NonNullable<FeedbackSnapshot['reason']> &
  ('ACCURATE' | 'CLEAR' | 'ACTIONABLE' | 'INCORRECT' | 'NOT_RELEVANT' | 'UNCLEAR' | 'MISSING_INFORMATION')
type MessageFeedbackSchema = components['schemas']['MessageFeedbackDTO']
export type MessageFeedback = Omit<Required<MessageFeedbackSchema>, 'rating' | 'reason'> & {
  rating: FeedbackRating
  reason: FeedbackReason
}

export function isFeedbackRating(value: unknown): value is FeedbackRating {
  return value === 'HELPFUL' || value === 'NOT_HELPFUL'
}

export function isFeedbackReason(value: unknown): value is FeedbackReason {
  return value === 'ACCURATE' || value === 'CLEAR' || value === 'ACTIONABLE'
    || value === 'INCORRECT' || value === 'NOT_RELEVANT' || value === 'UNCLEAR'
    || value === 'MISSING_INFORMATION'
}

export async function putMessageFeedback(messageId: string, rating: FeedbackRating, reason: FeedbackReason): Promise<MessageFeedback> {
  const response = await http.put<ApiResponse<FeedbackSnapshot>>(`/api/ai/feedback/${encodeURIComponent(messageId)}`, { rating, reason })
  const value = response.data.data
  if (!value || !isFeedbackRating(value.rating) || !isFeedbackReason(value.reason)
    || typeof value.createdAt !== 'string' || typeof value.updatedAt !== 'string') {
    throw new Error('反馈响应不符合契约')
  }
  return {
    rating: value.rating,
    reason: value.reason,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt
  }
}

export async function deleteMessageFeedback(messageId: string) {
  await http.delete(`/api/ai/feedback/${encodeURIComponent(messageId)}`)
}

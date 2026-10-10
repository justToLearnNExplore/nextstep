import type { Feed, FeedEvent, Severity } from './api';

/** Sample activity for the public demo, placed relative to "now" so it always reads as recent. */
export function demoFeed(): Feed {
  const now = Date.now() / 1000;
  const minutesAgo = (m: number) => now - m * 60;
  let n = 0;
  const ev = (time: number, kind: string, severity: Severity, title: string, detail = ''): FeedEvent => ({
    id: `demo-${n++}`, at: time, kind, severity, title, detail,
  });

  const events: FeedEvent[] = [
    ev(minutesAgo(6), 'task_done', 'done', 'Finished: Order one litre of milk on Blinkit', 'Order placed. Arriving in 12 minutes, pay ₹68 in cash.'),
    ev(minutesAgo(8), 'confirmed', 'confirmed', 'Place order: Amul Taaza toned milk 1 L, ₹68, cash on delivery, home address', 'Kamala said yes'),
    ev(minutesAgo(12), 'private', 'private', 'Kamala completed a private step', 'Signed in to Blinkit with an OTP. NextStep paused and never saw it.'),
    ev(minutesAgo(15), 'task_started', 'routine', 'Order one litre of milk on Blinkit', 'Kamala approved the plan.'),
    ev(minutesAgo(54), 'share_sent', 'confirmed', 'Sent medicine photo to Dr. Rao on WhatsApp', 'Kamala said yes'),
    ev(minutesAgo(56), 'medicine_read', 'routine', 'Read a medicine label', 'Dolo 650 (Paracetamol 650 mg) · expires 08/2027'),
    ev(minutesAgo(98), 'scam_blocked', 'alert', 'Scam message stopped', 'Pretended to be State Bank of India. Signs: a fake look-alike website, urgent or threatening language. Kamala was warned. NextStep offered the official State Bank of India website instead.'),
    ev(minutesAgo(139), 'risky_screen', 'alert', 'Explained a risky screen', 'This page is asking for your UPI PIN. Never share your PIN with anyone. I took you back.'),
    ev(minutesAgo(215), 'task_done', 'done', 'Finished: Play devotional songs on YouTube', 'Playing Suprabhatam.'),
    ev(minutesAgo(1560), 'declined', 'declined', 'Send "I will come tomorrow" to Ravi on WhatsApp', 'Kamala said no'),
    ev(minutesAgo(1563), 'task_started', 'routine', 'Message Ravi on WhatsApp', 'Kamala approved the plan.'),
    ev(minutesAgo(1705), 'task_done', 'done', 'Finished: Call Lakshmi', 'Call started.'),
    ev(minutesAgo(2025), 'viewer_added', 'routine', 'Ravi can now see this timeline'),
  ];
  return { senior_name: 'Kamala', language: 'kn-IN', last_seen: now - 4 * 60, events: events.sort((a, b) => b.at - a.at) };
}

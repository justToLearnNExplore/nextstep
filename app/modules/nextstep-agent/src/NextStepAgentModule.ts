import { NativeModule, requireNativeModule } from 'expo';

import type { AudioState, Heard, NextStepAgentEvents, NextStepConfig, RecentActivity } from './NextStepAgent.types';

declare class NextStepAgentModule extends NativeModule<NextStepAgentEvents> {
  isAccessibilityEnabled(): boolean;
  openAccessibilitySettings(): void;
  isNotificationAccessEnabled(): boolean;
  openNotificationAccessSettings(): void;
  configure(config: NextStepConfig): void;
  startTask(goal: string): void;
  stopTask(): void;
  understandScreen(): void;
  showBubble(): void;
  hideBubble(): void;
  /** Opens the recipient's WhatsApp chat with the photo; the overlay asks before tapping Send. */
  shareImageToWhatsApp(path: string, phone: string | null, caption: string, recipientName: string): void;
  getAudioState(): AudioState;
  /** "media" or "ringer". Returns "ok" or "needs_access" (Do Not Disturb access required). */
  fixSound(kind: 'media' | 'ringer'): 'ok' | 'needs_access';
  openDoNotDisturbAccess(): void;
  getRecentActivity(sinceMs: number): Promise<RecentActivity>;
  isAppInstalled(packageName: string): boolean;
  minimizeApp(): void;
  listenOnce(language: string): Promise<Heard>;
  cancelListening(): void;
  /** Android 10: true until the user allows screen capture for this session. */
  needsScreenCapturePermission(): boolean;
  requestScreenCapture(): Promise<boolean>;
}

export default requireNativeModule<NextStepAgentModule>('NextStepAgent');

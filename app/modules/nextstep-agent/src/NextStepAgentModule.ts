import { NativeModule, requireNativeModule } from 'expo';

import type { NextStepAgentEvents, NextStepConfig } from './NextStepAgent.types';

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
  isAppInstalled(packageName: string): boolean;
  minimizeApp(): void;
  listenOnce(language: string): Promise<string | null>;
  cancelListening(): void;
}

export default requireNativeModule<NextStepAgentModule>('NextStepAgent');

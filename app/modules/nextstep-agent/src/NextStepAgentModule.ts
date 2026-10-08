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
  isAppInstalled(packageName: string): boolean;
  minimizeApp(): void;
  listenOnce(language: string): Promise<string | null>;
  cancelListening(): void;
}

export default requireNativeModule<NextStepAgentModule>('NextStepAgent');

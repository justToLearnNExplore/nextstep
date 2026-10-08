import { requireNativeView } from 'expo';
import { forwardRef, type Ref } from 'react';
import type { NativeSyntheticEvent, ViewProps } from 'react-native';

export type GuidanceState = 'more_light' | 'hold_steady' | 'find_label' | 'move_closer' | 'move_back' | 'ready';

export type GuidanceEvent = {
  state: GuidanceState;
  text: string;
  brightness: number;
  sharpness: number;
  motion: number;
  lineHeight: number;
};

export type CapturedPhoto = { path: string; uri: string; text: string };

export type MedicineCameraHandle = {
  takePhoto(): Promise<CapturedPhoto>;
  setTorch(on: boolean): Promise<void>;
};

type Props = ViewProps & {
  onGuidance?: (e: NativeSyntheticEvent<GuidanceEvent>) => void;
  onCameraError?: (e: NativeSyntheticEvent<{ message: string }>) => void;
};

const NativeView = requireNativeView<Props & { ref?: Ref<MedicineCameraHandle> }>('NextStepMedicineCamera');

/** Native CameraX preview with on-device label-readability guidance (ML Kit). */
export const MedicineCamera = forwardRef<MedicineCameraHandle, Props>(function MedicineCamera(props, ref) {
  return <NativeView {...props} ref={ref} />;
});

package com.northstar.nextstep.agent.medicine

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class MedicineCameraModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("NextStepMedicineCamera")

    View(MedicineCameraView::class) {
      Events("onGuidance", "onCameraError")

      AsyncFunction("takePhoto") { view: MedicineCameraView, promise: Promise -> view.takePhoto(promise) }
      AsyncFunction("setTorch") { view: MedicineCameraView, on: Boolean -> view.setTorch(on) }

      OnViewDestroys { view: MedicineCameraView -> view.destroy() }
    }
  }
}

package info.cemu.cemu.emulation.emulatedusbdevices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.common.coroutines.RefreshableStateFlow
import info.cemu.cemu.nativeinterface.NativeEmulatedUSBDevices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class UsbDeviceEvent {
    object CreateFailed : UsbDeviceEvent()
    object LoadFailed : UsbDeviceEvent()
}

class EmulatedUSBDevicesViewModel : ViewModel() {
    val skylanderFigures = NativeEmulatedUSBDevices.getSkylanderFigures()
    val dimensionsMiniFigures = NativeEmulatedUSBDevices.getDimensionsMiniFigures()
    val infinityFigures = NativeEmulatedUSBDevices.getInfinityFigures()

    private val _events = MutableSharedFlow<UsbDeviceEvent>()
    val events = _events.asSharedFlow()

    private fun emitEvent(usbDeviceEvent: UsbDeviceEvent) {
        viewModelScope.launch { _events.emit(usbDeviceEvent) }
    }

    private fun getFigureSlots(maxSlots: Int, slotGetter: (Int) -> String?) =
        Array(maxSlots) { slotGetter(it) }

    val skylanderSlots = RefreshableStateFlow {
        getFigureSlots(
            NativeEmulatedUSBDevices.MAX_SKYLANDERS,
            NativeEmulatedUSBDevices::getSkylandersFigureSlot
        )
    }

    val infinitySlots = RefreshableStateFlow {
        getFigureSlots(
            NativeEmulatedUSBDevices.MAX_INFINITY_SLOTS,
            NativeEmulatedUSBDevices::getInfinityFigureSlot
        )
    }

    val dimensionsSlots = RefreshableStateFlow {
        getFigureSlots(
            NativeEmulatedUSBDevices.MAX_DIMENSIONS_SLOTS,
            NativeEmulatedUSBDevices::getDimensionsFigureSlot
        )
    }

    fun clearDimensionsFigure(pad: Int, index: Int) {
        NativeEmulatedUSBDevices.clearDimensionsFigure(pad, index)
        dimensionsSlots.refresh()
    }

    fun clearInfinityFigure(slot: Int) {
        NativeEmulatedUSBDevices.clearInfinityFigure(slot)
        infinitySlots.refresh()
    }

    // Paths of the figure files loaded into each Skylander slot, so the portal screen can tell which
    // installed figures are on the portal (the native side only reports the figure's name).
    private val _skylanderSlotPaths =
        MutableStateFlow(arrayOfNulls<String>(NativeEmulatedUSBDevices.MAX_SKYLANDERS).toList())
    val skylanderSlotPaths = _skylanderSlotPaths.asStateFlow()

    private fun setSkylanderSlotPath(slot: Int, path: String?) =
        _skylanderSlotPaths.update { paths -> paths.toMutableList().also { it[slot] = path } }

    private val _isSwappingSkylander = MutableStateFlow(false)
    val isSwappingSkylander = _isSwappingSkylander.asStateFlow()

    fun clearSkylandersFigure(slot: Int) {
        NativeEmulatedUSBDevices.clearSkylandersFigure(slot)
        setSkylanderSlotPath(slot, null)
        skylanderSlots.refresh()
    }

    fun clearAllSkylanderFigures() {
        for (slot in 0..<NativeEmulatedUSBDevices.MAX_SKYLANDERS) {
            if (skylanderSlots.state.value[slot] != null) {
                clearSkylandersFigure(slot)
            }
        }
    }

    /**
     * Puts [figure] into [slot]. If the slot is already occupied the old figure is removed first and
     * the new one is placed after a short delay, since some games miss an instant swap.
     */
    fun placeSkylanderFigure(figure: NativeEmulatedUSBDevices.InstalledFigure, slot: Int) {
        if (_isSwappingSkylander.value) {
            return
        }
        if (skylanderSlots.state.value[slot] == null) {
            loadSkylanderFigure(figure, slot)
            return
        }
        _isSwappingSkylander.value = true
        viewModelScope.launch {
            try {
                clearSkylandersFigure(slot)
                delay(SKYLANDER_SWAP_DELAY_MS)
                loadSkylanderFigure(figure, slot)
            } finally {
                _isSwappingSkylander.value = false
            }
        }
    }

    val installedSkylanderFigures =
        RefreshableStateFlow(NativeEmulatedUSBDevices::getInstalledSkylanderFigures)

    val installedDimensionMiniFigures =
        RefreshableStateFlow(NativeEmulatedUSBDevices::getInstalledDimensionsMiniFigures)

    val installedInfinityFigures =
        RefreshableStateFlow(NativeEmulatedUSBDevices::getInstalledInfinityFigures)

    private fun deleteFigure(
        installedFigure: NativeEmulatedUSBDevices.InstalledFigure, afterDelete: () -> Unit
    ) {
        NativeEmulatedUSBDevices.deleteFigure(installedFigure)
        afterDelete()
    }

    fun deleteInstalledSkylanderFigure(installedFigure: NativeEmulatedUSBDevices.InstalledFigure) =
        deleteFigure(installedFigure, installedSkylanderFigures::refresh)

    fun deleteInstalledDimensionsFigure(installedFigure: NativeEmulatedUSBDevices.InstalledFigure) =
        deleteFigure(installedFigure, installedDimensionMiniFigures::refresh)

    fun deleteInstalledInfinityFigure(installedFigure: NativeEmulatedUSBDevices.InstalledFigure) =
        deleteFigure(installedFigure, installedInfinityFigures::refresh)

    fun createSkylanderFigure(id: Int, variant: Int) {
        if (!NativeEmulatedUSBDevices.createSkylanderFigure(id, variant)) {
            emitEvent(UsbDeviceEvent.CreateFailed)
            return
        }
        installedSkylanderFigures.refresh()
    }

    fun createDimensionsMiniFigure(number: Long) {
        if (!NativeEmulatedUSBDevices.createDimensionsFigure(number)) {
            emitEvent(UsbDeviceEvent.CreateFailed)
            return
        }
        installedDimensionMiniFigures.refresh()
    }

    fun createInfinityFigure(number: Long) {
        if (!NativeEmulatedUSBDevices.createInfinityFigure(number)) {
            emitEvent(UsbDeviceEvent.CreateFailed)
            return
        }
        installedInfinityFigures.refresh()
    }

    fun loadSkylanderFigure(figure: NativeEmulatedUSBDevices.InstalledFigure, slot: Int) {
        if (!NativeEmulatedUSBDevices.loadSkylandersFigure(figure.path, slot)) {
            emitEvent(UsbDeviceEvent.LoadFailed)
            return
        }
        setSkylanderSlotPath(slot, figure.path)
        skylanderSlots.refresh()
    }

    fun loadDimensionsFigure(
        figure: NativeEmulatedUSBDevices.InstalledFigure, pad: Int, index: Int
    ) {
        if (!NativeEmulatedUSBDevices.loadDimensionsFigure(figure.path, pad, index)) {
            emitEvent(UsbDeviceEvent.LoadFailed)
            return
        }
        dimensionsSlots.refresh()
    }

    fun loadInfinityFigure(figure: NativeEmulatedUSBDevices.InstalledFigure, slot: Int) {
        if (!NativeEmulatedUSBDevices.loadInfinityFigure(figure.path, slot)) {
            emitEvent(UsbDeviceEvent.LoadFailed)
            return
        }
        infinitySlots.refresh()
    }

    fun tempRemoveDimensionsFigure(index: Int) =
        NativeEmulatedUSBDevices.tempRemoveDimensionsFigure(index)

    fun cancelRemoveDimensionsFigure(index: Int) =
        NativeEmulatedUSBDevices.cancelRemoveDimensionsFigure(index)

    fun moveDimensionsFigure(pad: Int, index: Int, oldPad: Int, oldIndex: Int) {
        NativeEmulatedUSBDevices.moveDimensionsFigure(pad, index, oldPad, oldIndex)
        dimensionsSlots.refresh()
    }

    companion object {
        const val SKYLANDER_SWAP_DELAY_MS = 500L
    }
}

#include "input.h"
#include "inputdescriptors.h"
#include "contentaspect.h"
#include <android/input.h>
#include <android/keycodes.h>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <thread>

using libretrodroid::Input;
static constexpr unsigned L2 = RETRO_DEVICE_ID_JOYPAD_L2;
static constexpr unsigned R2 = RETRO_DEVICE_ID_JOYPAD_R2;
static int pressure(Input& input, unsigned id, unsigned port = 0) {
    return input.getInputState(port, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_BUTTON, id);
}

static void fractionalAndDigitalInput() {
    Input input;
    input.onInputSnapshot(0, (1u << R2) | (1u << RETRO_DEVICE_ID_JOYPAD_A), .5f, -.5f, 1.f, -1.f, .25f, .75f);
    assert(pressure(input, L2) == 8192);
    assert(pressure(input, R2) == 24575);
    assert(input.getInputState(0, RETRO_DEVICE_JOYPAD, 0, L2) == 0);
    assert(input.getInputState(0, RETRO_DEVICE_JOYPAD, 0, R2) == 1);
    assert(pressure(input, RETRO_DEVICE_ID_JOYPAD_A) == 32767);
    assert(input.getInputState(0, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y) == -32767);
    input.onKeyEvent(1, AKEY_EVENT_ACTION_DOWN, AKEYCODE_BUTTON_L2);
    assert(pressure(input, L2, 1) == 32767); // legacy API derives analog values from its digital keys.
    input.onKeyEvent(1, AKEY_EVENT_ACTION_UP, AKEYCODE_BUTTON_L2);
    assert(pressure(input, L2, 1) == 32767);
    input.flushPendingReleases();
    assert(pressure(input, L2, 1) == 0);
}

static void fastTapAndIndependentPorts() {
    Input input;
    input.onInputSnapshot(0, 1u << L2, 0.f, 0.f, 0.f, 0.f, .7f, 0.f);
    input.onInputSnapshot(1, 1u << R2, 0.f, 0.f, 0.f, 0.f, 0.f, .9f);
    input.onInputSnapshot(0, 0, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    assert(input.getInputState(0, RETRO_DEVICE_JOYPAD, 0, L2) == 1);
    assert(pressure(input, L2) == std::lround(.7f * 32767));
    assert(pressure(input, R2, 1) == std::lround(.9f * 32767));
    input.flushPendingReleases();
    assert(input.getInputState(0, RETRO_DEVICE_JOYPAD, 0, L2) == 0);
    assert(pressure(input, L2) == 0);
    assert(pressure(input, R2, 1) == std::lround(.9f * 32767));
    // Partial pressure below the digital threshold is retained too.
    input.onInputSnapshot(0, 0, 0.f, 0.f, 0.f, 0.f, .2f, 0.f);
    input.onInputSnapshot(0, 0, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    assert(pressure(input, L2) == std::lround(.2f * 32767));
    input.flushPendingReleases();
    assert(pressure(input, L2) == 0);
}

static void everyDigitalButtonAndLegacyDiagonal() {
    Input input;
    for (unsigned port = 0; port < 4; ++port) {
        for (unsigned id = 0; id < 16; ++id) {
            input.onInputSnapshot(port, 1u << id, 0.f, 0.f, 0.f, 0.f, id == L2 ? 1.f : 0.f, id == R2 ? 1.f : 0.f);
            input.onInputSnapshot(port, 0, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
            assert(input.getInputState(port, RETRO_DEVICE_JOYPAD, 0, id) == 1);
            assert(pressure(input, id, port) == 32767);
            input.flushPendingReleases();
            assert(input.getInputState(port, RETRO_DEVICE_JOYPAD, 0, id) == 0);
            assert(pressure(input, id, port) == 0);
        }
    }
    const int keys[] = {AKEYCODE_DPAD_UP_LEFT, AKEYCODE_DPAD_UP_RIGHT, AKEYCODE_DPAD_DOWN_LEFT, AKEYCODE_DPAD_DOWN_RIGHT};
    const unsigned masks[] = {
        (1u << RETRO_DEVICE_ID_JOYPAD_UP) | (1u << RETRO_DEVICE_ID_JOYPAD_LEFT),
        (1u << RETRO_DEVICE_ID_JOYPAD_UP) | (1u << RETRO_DEVICE_ID_JOYPAD_RIGHT),
        (1u << RETRO_DEVICE_ID_JOYPAD_DOWN) | (1u << RETRO_DEVICE_ID_JOYPAD_LEFT),
        (1u << RETRO_DEVICE_ID_JOYPAD_DOWN) | (1u << RETRO_DEVICE_ID_JOYPAD_RIGHT),
    };
    for (unsigned i = 0; i < 4; ++i) {
        input.onKeyEvent(0, AKEY_EVENT_ACTION_DOWN, keys[i]);
        assert(static_cast<unsigned>(input.getInputState(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK)) == masks[i]);
        input.onKeyEvent(0, AKEY_EVENT_ACTION_UP, keys[i]);
        assert(input.getInputPortBitmask(0) == masks[i]);
        assert(input.getInputPortBitmask(0) == 0);
    }
    assert(input.getInputState(0, RETRO_DEVICE_JOYPAD, 0, 9999) == 0);
}

static void netplayCaptureAndAuthoritativeFrames() {
    Input input;
    input.setNetplayActive(true);
    input.onInputSnapshot(0, 1u << L2, .4f, 0.f, 0.f, 0.f, .6f, 0.f);
    input.onInputSnapshot(0, 0, .4f, 0.f, 0.f, 0.f, 0.f, 0.f);
    input.setInputPortSnapshot(0, 1u << R2, -.8f, 0.f, 0.f, 0.f, 0.f, .8f);
    assert(pressure(input, R2) == std::lround(.8f * 32767));
    int frame[7];
    input.getInputPortSnapshot(0, frame);
    assert(frame[0] == (1u << L2));
    assert(frame[1] == std::lround(.4f * 32767));
    assert(frame[5] == std::lround(.6f * 32767));
    input.getInputPortSnapshot(0, frame);
    assert(frame[0] == 0 && frame[5] == 0); // exactly one sampled fast tap.
    assert(pressure(input, R2) == std::lround(.8f * 32767)); // capture never changes authoritative inputs.
    input.setInputPortSnapshot(0, 0, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f);
    assert(pressure(input, R2) == 0); // authoritative release is immediate.
    input.onInputSnapshot(0, 1u << L2, .3f, 0.f, 0.f, 0.f, .4f, 0.f);
    input.setInputPortSnapshot(1, 1u << R2, 0.f, 0.f, 0.f, 0.f, 0.f, 1.f);
    input.setNetplayActive(false);
    assert(pressure(input, L2) == std::lround(.4f * 32767));
    assert(pressure(input, R2, 1) == 0); // remote peer's final frame is not stuck after disconnect.
    input.reset();
    assert(input.getInputPortBitmask(0) == 0 && pressure(input, L2) == 0);
}

static void finiteBoundsAndAtomicSampling() {
    Input input;
    const float nan = std::numeric_limits<float>::quiet_NaN();
    input.onInputSnapshot(0, 0, nan, -2.f, 2.f, nan, nan, 2.f);
    int frame[7];
    input.getInputPortSnapshot(0, frame);
    assert(frame[1] == 0 && frame[2] == -32767 && frame[3] == 32767 && frame[4] == 0);
    assert(frame[5] == 0 && frame[6] == 32767);
    input.onInputSnapshot(99, 0xffff, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f);
    input.getInputPortSnapshot(99, frame);
    for (int value : frame) assert(value == 0);
    input.reset();
    std::thread writer([&] {
        for (int i = 0; i < 10000; ++i) {
            const float value = i % 2 ? .25f : .75f;
            input.onInputSnapshot(0, 0, value, value, value, value, value, value);
        }
    });
    for (int i = 0; i < 10000; ++i) {
        input.getInputPortSnapshot(0, frame);
        for (int j = 2; j < 7; ++j) assert(frame[j] == frame[1]);
    }
    writer.join();
}

static void descriptorOwnershipAndBounds() {
    InputDescriptorStore store;
    char action[] = "Fire: primary";
    retro_input_descriptor descriptors[] = {{0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A, action}, {}};
    assert(store.replace(descriptors));
    const unsigned revision = store.generation();
    assert(revision == 1 && store.snapshot().at(0) == "0:1:0:8:Fire: primary");
    store.replace(descriptors);
    assert(store.generation() == revision);
    action[0] = 'T';
    assert(store.snapshot().at(0) == "0:1:0:8:Fire: primary");
    store.replace(descriptors);
    assert(store.generation() == revision + 1);
    std::string longLabel(400, 'A');
    std::vector<retro_input_descriptor> many(300, {0, RETRO_DEVICE_JOYPAD, 0, 0, longLabel.c_str()});
    store.replace(many.data());
    const auto rows = store.snapshot();
    assert(rows.size() == 256 && rows.front().size() == std::string("0:1:0:0:").size() + 256);
    retro_input_descriptor invalid[] = {{4, RETRO_DEVICE_JOYPAD, 0, 0, "wrong port"}, {0, 250, 0, 0, "wrong device"}, {}};
    store.replace(invalid);
    assert(store.snapshot().empty());
    store.replace(descriptors);
    store.replace(nullptr);
    const unsigned cleared = store.generation();
    assert(store.snapshot().empty());
    store.replace(nullptr);
    assert(store.generation() == cleared);
}

static void contentAspectGeometryAndRotation() {
    using libretrodroid::aspectRatioForGeometry;
    using libretrodroid::contentRotationSwapsAspect;
    const float fourThirds = 4.0F / 3.0F;
    const float wide = 16.0F / 9.0F;
    assert(aspectRatioForGeometry(fourThirds, 640, 224) == fourThirds);
    assert(aspectRatioForGeometry(0.0F, 320, 240) == fourThirds);
    assert(aspectRatioForGeometry(-1.0F, 240, 320) == 0.75F);
    assert(aspectRatioForGeometry(std::numeric_limits<float>::quiet_NaN(), 320, 240) == fourThirds);
    assert(aspectRatioForGeometry(std::numeric_limits<float>::infinity(), 320, 240) == fourThirds);
    assert(aspectRatioForGeometry(0.0F, 0, 240) == 0.0F);
    assert(aspectRatioForGeometry(0.0F, 320, 0) == 0.0F);

    assert(!contentRotationSwapsAspect(-1, 1)); // automatic core rotation is already in its aspect.
    assert(!contentRotationSwapsAspect(90, 1));
    assert(!contentRotationSwapsAspect(270, 1)); // opposite direction, same portrait aspect.
    assert(contentRotationSwapsAspect(0, 1));
    assert(contentRotationSwapsAspect(90, 0));
    assert(contentRotationSwapsAspect(270, 0));
    assert(!contentRotationSwapsAspect(180, 0));

    libretrodroid::ContentAspectSnapshot snapshot;
    assert(snapshot.get() == 0.0F);
    snapshot.publish(aspectRatioForGeometry(0.0F, 320, 240), false);
    assert(snapshot.get() == fourThirds);
    // Runtime geometry can replace the load-time aspect without recreating the observer.
    snapshot.publish(aspectRatioForGeometry(0.0F, 240, 320), false);
    assert(snapshot.get() == 0.75F);
    snapshot.publish(wide, false); // a requested aspect (or primary screen split).
    assert(snapshot.get() == wide);
    snapshot.publish(wide, contentRotationSwapsAspect(90, 0));
    assert(std::fabs(snapshot.get() - 9.0F / 16.0F) < 0.00001F);
    snapshot.publish(std::numeric_limits<float>::infinity(), true);
    assert(snapshot.get() == 0.0F);
    snapshot.publish(wide, false);
    snapshot.reset(); // unload must never leave the previous game's shape visible.
    assert(snapshot.get() == 0.0F);
}

static void contentAspectConcurrentObservers() {
    libretrodroid::ContentAspectSnapshot snapshot;
    std::thread writer([&] {
        for (int i = 0; i < 10000; ++i) {
            snapshot.publish(2.0F, i % 2 == 0);
            if (i % 3 == 0) snapshot.reset();
        }
    });
    for (int i = 0; i < 10000; ++i) {
        const float aspect = snapshot.get();
        assert(aspect == 0.0F || aspect == 0.5F || aspect == 2.0F);
    }
    writer.join();
    snapshot.reset();
    assert(snapshot.get() == 0.0F);
}

static void contentFrameSizeSnapshot() {
    libretrodroid::ContentAspectSnapshot snapshot;
    assert(snapshot.getFrame() == 0U);
    snapshot.publishFrame(240, 160);
    assert((snapshot.getFrame() >> 32U) == 240U);
    assert((snapshot.getFrame() & 0xFFFFFFFFULL) == 160U);
    // A missing dimension is unknown, never a degenerate whole-number base.
    snapshot.publishFrame(240, 0);
    assert(snapshot.getFrame() == 0U);
    snapshot.publishFrame(256, 224);
    snapshot.publish(4.0F / 3.0F, false);
    snapshot.reset(); // unload clears the size together with the aspect.
    assert(snapshot.getFrame() == 0U);
    assert(snapshot.get() == 0.0F);
}

int main() {
    fractionalAndDigitalInput();
    fastTapAndIndependentPorts();
    everyDigitalButtonAndLegacyDiagonal();
    netplayCaptureAndAuthoritativeFrames();
    finiteBoundsAndAtomicSampling();
    descriptorOwnershipAndBounds();
    contentAspectGeometryAndRotation();
    contentAspectConcurrentObservers();
    contentFrameSizeSnapshot();
    std::cout << "9 native input/display contract groups passed (including concurrent snapshots)\n";
}

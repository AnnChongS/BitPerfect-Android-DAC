#pragma once

#include <cstdint>

namespace bitperfect::uac {

// Standard USB Audio Class / SubClass / Protocol identifiers
constexpr uint8_t USB_CLASS_AUDIO              = 0x01;
constexpr uint8_t SUBCLASS_UNDEFINED           = 0x00;
constexpr uint8_t SUBCLASS_AUDIOCONTROL        = 0x01;
constexpr uint8_t SUBCLASS_AUDIOSTREAMING      = 0x02;
constexpr uint8_t SUBCLASS_MIDISTREAMING       = 0x03;

// Interface Protocols (bInterfaceProtocol)
constexpr uint8_t PROTOCOL_UAC1                = 0x00;
constexpr uint8_t PROTOCOL_UAC2                = 0x20; // IP_VERSION_02_00
constexpr uint8_t PROTOCOL_UAC3                = 0x30; // IP_VERSION_03_00

// Class-Specific Descriptor Types (bDescriptorType)
constexpr uint8_t DT_CS_INTERFACE              = 0x24;
constexpr uint8_t DT_CS_ENDPOINT               = 0x25;

// AudioControl (AC) Class-Specific Descriptor Subtypes
constexpr uint8_t AC_HEADER                    = 0x01;
constexpr uint8_t AC_INPUT_TERMINAL            = 0x02;
constexpr uint8_t AC_OUTPUT_TERMINAL           = 0x03;
constexpr uint8_t AC_MIXER_UNIT                = 0x04;
constexpr uint8_t AC_SELECTOR_UNIT             = 0x05;
constexpr uint8_t AC_FEATURE_UNIT              = 0x06;
constexpr uint8_t AC_EFFECT_UNIT               = 0x07; // UAC2
constexpr uint8_t AC_PROCESSING_UNIT           = 0x08; // UAC2 (0x07 in UAC1)
constexpr uint8_t AC_EXTENSION_UNIT            = 0x09; // UAC2 (0x08 in UAC1)
constexpr uint8_t AC_CLOCK_SOURCE              = 0x0A; // UAC2
constexpr uint8_t AC_CLOCK_SELECTOR            = 0x0B; // UAC2
constexpr uint8_t AC_CLOCK_MULTIPLIER          = 0x0C; // UAC2
constexpr uint8_t AC_SAMPLE_RATE_CONVERTER     = 0x0D; // UAC2

// AudioStreaming (AS) Class-Specific Descriptor Subtypes
constexpr uint8_t AS_GENERAL                   = 0x01;
constexpr uint8_t AS_FORMAT_TYPE               = 0x02;
constexpr uint8_t AS_FORMAT_SPECIFIC           = 0x03;

// Endpoint Class-Specific Descriptor Subtypes
constexpr uint8_t EP_GENERAL                   = 0x01;

// Format Type Codes
constexpr uint8_t FORMAT_TYPE_I                = 0x01;
constexpr uint8_t FORMAT_TYPE_II               = 0x02;
constexpr uint8_t FORMAT_TYPE_III              = 0x03;

// UAC 1.0 Format Tags (wFormatTag)
constexpr uint16_t UAC1_FORMAT_PCM             = 0x0001;
constexpr uint16_t UAC1_FORMAT_PCM8            = 0x0002;
constexpr uint16_t UAC1_FORMAT_IEEE_FLOAT      = 0x0003;

// UAC 2.0 Type I Format Bitmask (bmFormats)
constexpr uint32_t UAC2_FORMAT_PCM             = 1u << 0;
constexpr uint32_t UAC2_FORMAT_PCM8            = 1u << 1;
constexpr uint32_t UAC2_FORMAT_IEEE_FLOAT      = 1u << 2;
constexpr uint32_t UAC2_FORMAT_RAW_DATA        = 1u << 31;

// Terminal Types
constexpr uint16_t TERMINAL_USB_STREAMING      = 0x0101;
constexpr uint16_t TERMINAL_OUTPUT_UNDEFINED   = 0x0300;
constexpr uint16_t TERMINAL_SPEAKER            = 0x0301;
constexpr uint16_t TERMINAL_HEADPHONES         = 0x0302;
constexpr uint16_t TERMINAL_DESKTOP_SPEAKER    = 0x0304;
constexpr uint16_t TERMINAL_LINE_CONNECTOR     = 0x0603;
constexpr uint16_t TERMINAL_SPDIF_INTERFACE    = 0x0605;

// Endpoint bmAttributes bitfields
constexpr uint8_t EP_TRANSFER_TYPE_MASK        = 0x03;
constexpr uint8_t EP_TRANSFER_TYPE_ISO         = 0x01;
constexpr uint8_t EP_SYNC_TYPE_SHIFT           = 2;
constexpr uint8_t EP_SYNC_TYPE_MASK            = 0x03;
constexpr uint8_t EP_USAGE_TYPE_SHIFT          = 4;
constexpr uint8_t EP_USAGE_TYPE_MASK           = 0x03;
constexpr uint8_t EP_USAGE_DATA                = 0x00;
constexpr uint8_t EP_USAGE_FEEDBACK            = 0x01;
constexpr uint8_t EP_USAGE_IMPLICIT_FEEDBACK   = 0x02;
constexpr uint8_t EP_DIR_IN_MASK               = 0x80;

// UAC 1.0 Class-Specific Request Codes
constexpr uint8_t UAC1_SET_CUR                 = 0x01;
constexpr uint8_t UAC1_GET_CUR                 = 0x81;
constexpr uint8_t UAC1_GET_MIN                 = 0x82;
constexpr uint8_t UAC1_GET_MAX                 = 0x83;
constexpr uint8_t UAC1_GET_RES                 = 0x84;

// UAC 1.0 Endpoint Control Selectors
constexpr uint8_t UAC1_EP_CS_SAMPLING_FREQ     = 0x01;

// UAC 2.0 Class-Specific Request Codes
constexpr uint8_t UAC2_REQ_CUR                 = 0x01;
constexpr uint8_t UAC2_REQ_RANGE               = 0x02;

// UAC 2.0 Clock Source Control Selectors
constexpr uint8_t UAC2_CS_SAM_FREQ_CONTROL     = 0x01;
constexpr uint8_t UAC2_CS_CLOCK_VALID_CONTROL  = 0x02;

// UAC 2.0 Clock Selector Control Selectors
constexpr uint8_t UAC2_CX_CLOCK_SELECTOR_CTRL  = 0x01;

// Feature Unit Control Selectors (identical values in UAC 1.0 & UAC 2.0)
constexpr uint8_t FU_MUTE_CONTROL              = 0x01;
constexpr uint8_t FU_VOLUME_CONTROL            = 0x02;

// USB bmRequestType shortcuts for UAC class requests
constexpr uint8_t REQ_TYPE_CLASS_IF_IN         = 0xA1; // IN  | CLASS | INTERFACE
constexpr uint8_t REQ_TYPE_CLASS_IF_OUT        = 0x21; // OUT | CLASS | INTERFACE
constexpr uint8_t REQ_TYPE_CLASS_EP_IN         = 0xA2; // IN  | CLASS | ENDPOINT
constexpr uint8_t REQ_TYPE_CLASS_EP_OUT        = 0x22; // OUT | CLASS | ENDPOINT

// Standard Hi-Res Audio Sample Rates (used when intersecting continuous ranges)
constexpr uint32_t STANDARD_SAMPLE_RATES[] = {
    44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
};

} // namespace bitperfect::uac

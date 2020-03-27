# Cordova Plugin for Twilio
Cordova Plugin for Twilio SDK, based on https://github.com/jefflinwood/cordova-plugin-twiliovoicesdk

The aim of this package is to bring jefflinwoods work up to date with the latest SDKS and add further Twilio features as it is developed

## Preferences

There are three preferences you will need to configure:

Preference | Example | Description
---------- | ------- | -----------
INCOMING_CALL_APP_NAME | PhoneApp | Users will get a notification that they have an inbound call (either a standard Push notification, or a CallKit screen) - this name is shown to the users.
ENABLE_CALL_KIT | true | This plugin has optional CallKit support for iOS 10 and above. ENABLE_CALL_KIT should be "true" or "false"
MASK_INCOMING_PHONE_NUMBER | false | This plugin has optional ability to mask the incoming phone number. MASK_INCOMING_PHONE_NUMBER should be "true" or "false"
DEBUG_TWILIO | false | Optionally enable twilio library debugging. DEBUG_TWILIO should be "true" or "false"

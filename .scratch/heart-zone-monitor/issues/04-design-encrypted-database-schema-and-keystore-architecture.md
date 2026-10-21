# Design encrypted database schema and keystore architecture

Type: grilling
Status: open
Blocked by: none

## Question

How should the Room database schema for Sessions and 1Hz HeartRateSamples be designed, how will the 256-bit AES master key be generated/retrieved from Android Keystore, and how will migrations and lifecycle initialization of SQLCipher be managed securely offline?

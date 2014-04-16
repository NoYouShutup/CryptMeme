CryptMeme
=========

CryptMeme is a highly-encrypted, high-privacy, and mostly anonymous distributed social network based on the I2P network.

The basic idea is that it turns social networking into a P2P system rather than a centralized system. User content is stored locally, and you only stores what you need. Data is requested directly from friends rather than a central service. Everything of course will be encrypted and signed according to privacy and access settings.

Right now I'm planning on embedding the I2P router so that an install of I2P is not necessary. This has the added benefit of making it so anybody who uses this service can also see other eepsites through their locally running proxy.

Unfortunately the only way to really make this work well is to have a computer running your service 24/7 (or close to it). I'm assuming that as I2P gets more and more adopted this will be less of a problem. I understand that not everyone has a computer connected to the internet they can dedicate to run their own "profile server", but I'm considering options to help make that better/easier to deal with. Also, it does have the added benefit of giving you the ability to access your own profile from wherever (by connected to your profile server) so adding mobile app functionality should be easy.

I've decided to do this as a locally running grails service so that everything stays open-sourced and java-based. That should make portability a non-issue in theory.

This is currently a work in progress. If you would like to contribute, please send an email to admin@cryptmeme.org.

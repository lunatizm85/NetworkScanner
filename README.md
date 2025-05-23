# NetworkScanner Android App

<div align="center">
  <h3>🔍 Advanced Network Discovery & Management Tool</h3>
  <p>A comprehensive Android application for network scanning, device discovery, printer management, and network performance testing.</p>
</div>

## 📱 Features

### 🌐 Network Discovery
- **Multiple Discovery Methods**: IP scanning, ARP scanning, mDNS/NSD discovery, and UDP broadcasting
- **Device Detection**: Automatically discovers and identifies network devices
- **Smart Printer Detection**: Specialized detection for network printers with port scanning
- **Real-time Updates**: Live device status monitoring with latency information

### 🖨️ Printer Management
- **Xprinter Integration**: Full support for Xprinter network configuration
- **Multi-Protocol Support**: Compatible with IPP, RAW/JetDirect, and LPD protocols
- **Network Configuration**: Easy printer network setup and management
- **Port Scanning**: Automatic detection of printer services on common ports (9100, 515, 631, etc.)

### ⚡ Network Performance Testing
- **LAN Speed Testing**: Local network speed assessment
- **Online Speed Testing**: Internet connectivity and speed measurement
- **Latency Monitoring**: Real-time ping and response time tracking
- **Quality Assessment**: Visual indicators for network performance quality

### 🔍 Health Monitoring
- **Service Health Checks**: Monitor external service availability
- **Pre-configured Services**: 
  - BeepIT (beepit.com)
  - StoreHub HQ (storehubhq.com)
  - StoreHub Merchandise (storehub.me)
  - Payment API (payment.storehubhq.com)
- **Custom Service Monitoring**: Add your own services to monitor

### 📊 Advanced Features
- **ARP Table Analysis**: Deep network analysis using ARP scanning
- **Multi-subnet Discovery**: Detect devices across different network segments
- **Device Categorization**: Automatic classification of discovered devices
- **Export Capabilities**: Share and export scan results
- **Real-time Logging**: Comprehensive app activity logging

## 🛠️ Technical Specifications

### Requirements
- **Android Version**: API Level 24+ (Android 7.0)
- **Permissions**: 
  - Network access
  - WiFi state access
  - Location (for network scanning)
- **Network**: WiFi connection required for full functionality

### Architecture
- **Language**: Kotlin
- **UI Framework**: Material Design Components
- **Concurrency**: Kotlin Coroutines
- **Networking**: OkHttp, Native Android APIs
- **Printer SDK**: Xprinter SDK integration

### Dependencies
```gradle
// Core Android
androidx.core:core-ktx:1.12.0
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0

// Networking & Concurrency
kotlinx-coroutines-android:1.7.3
okhttp3:okhttp:4.9.3

// Printer Integration
printer-lib-3.1.4 (Xprinter SDK)

// Data Processing
gson:2.9.0
```

## 🚀 Installation

### From Source
1. **Clone the repository**
   ```bash
   git clone https://github.com/lunatizm85/NetworkScanner.git
   cd NetworkScanner
   ```

2. **Open in Android Studio**
   - Import the project in Android Studio
   - Sync Gradle files
   - Ensure you have Android SDK 34 installed

3. **Add Xprinter SDK**
   - Place the `printer-lib-3.1.4.aar` file in the `app/libs/` directory
   - The Gradle file is already configured to include it

4. **Build and Install**
   ```bash
   ./gradlew assembleDebug
   # or build directly in Android Studio
   ```

### APK Installation
*APK releases will be available in the Releases section*

## 📖 Usage Guide

### Basic Network Scanning
1. **Launch the app** and grant required permissions
2. **Connect to WiFi** network you want to scan
3. **Tap "Scan Network"** to start discovery
4. **View results** in the device list with real-time updates

### Printer Setup
1. **Scan network** to discover printers
2. **Tap on printer device** to access configuration
3. **Configure network settings** using the built-in dialog
4. **Test connection** and save settings

### Speed Testing
1. **Tap "Speed Test"** button
2. **Wait for LAN speed** assessment
3. **View results** with quality indicators
4. **Check latency** and connection quality

### Health Monitoring
1. **View service status** in the health panel
2. **Tap refresh** to update service states
3. **Monitor** critical services in real-time

## 🔧 Configuration

### Network Settings
- **Timeout Values**: Configurable scan timeouts
- **Port Ranges**: Customizable port scanning ranges
- **Discovery Methods**: Enable/disable specific discovery protocols

### Printer Configuration
- **IP Assignment**: Static or DHCP configuration
- **Port Settings**: Custom port assignments
- **Protocol Selection**: Choose communication protocols

## 📱 Screenshots

*Screenshots will be added to showcase the app interface*

## 🤝 Contributing

We welcome contributions! Please follow these steps:

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/AmazingFeature`)
3. **Commit** your changes (`git commit -m 'Add some AmazingFeature'`)
4. **Push** to the branch (`git push origin feature/AmazingFeature`)
5. **Open** a Pull Request

### Development Guidelines
- Follow Kotlin coding conventions
- Add comments for complex logic
- Update documentation for new features
- Test on multiple Android versions

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🐛 Known Issues

- Some routers may block ARP scanning
- Printer discovery depends on network configuration
- Speed test accuracy varies with network conditions

## 🔄 Changelog

### Version 1.0.0
- Initial release
- Basic network scanning functionality
- Printer discovery and configuration
- Speed testing capabilities
- Health monitoring system

## 📞 Support

For support and questions:
- **Issues**: Use GitHub Issues for bug reports
- **Features**: Request features through GitHub Issues
- **Documentation**: Check the wiki for detailed guides

## 🙏 Acknowledgments

- **Xprinter** for the printer SDK
- **Material Design** for UI components
- **Android Community** for networking libraries
- **Contributors** who help improve this project

---

<div align="center">
  <p>Built with ❤️ for network administrators and IT professionals</p>
  <p>⭐ Star this repository if you find it helpful!</p>
</div> 
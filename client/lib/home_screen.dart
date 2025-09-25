
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_sound/flutter_sound.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:provider/provider.dart';
import 'activity_model.dart';
import 'api_service.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final ApiService _apiService = ApiService();
  Future<Activity>? _activityFuture;
  final FlutterSoundRecorder _recorder = FlutterSoundRecorder();
  bool _isRecording = false;
  String? _filePath;

  @override
  void initState() {
    super.initState();
    _initRecorder();
  }

  @override
  void dispose() {
    _recorder.closeRecorder();
    super.dispose();
  }

  Future<void> _initRecorder() async {
    final status = await Permission.microphone.request();
    if (status != PermissionStatus.granted) {
      throw RecordingPermissionException('Microphone permission not granted');
    }
    await _recorder.openRecorder();
  }

  Future<void> _startRecording() async {
    final directory = await getApplicationDocumentsDirectory();
    _filePath = '${directory.path}/temp.wav';
    await _recorder.startRecorder(toFile: _filePath, codec: Codec.pcm16WAV);
    setState(() {
      _isRecording = true;
    });
  }

  Future<void> _stopRecording() async {
    await _recorder.stopRecorder();
    setState(() {
      _isRecording = false;
      if (_filePath != null) {
        _activityFuture = _apiService.uploadVoice(_filePath!);
      }
    });
  }

  void _toggleRecording() {
    if (_isRecording) {
      _stopRecording();
    } else {
      _startRecording();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Voice Activity Logger'),
        actions: [
          IconButton(
            icon: Icon(context.watch<ThemeProvider>().themeMode == ThemeMode.dark
                ? Icons.light_mode
                : Icons.dark_mode),
            onPressed: () => context.read<ThemeProvider>().toggleTheme(),
            tooltip: 'Toggle Theme',
          ),
        ],
      ),
      body: Center(
        child: _activityFuture == null
            ? Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    _isRecording ? 'Recording...' : 'Press the button to start recording',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 20),
                  FloatingActionButton(
                    onPressed: _toggleRecording,
                    tooltip: 'Record',
                    child: Icon(_isRecording ? Icons.stop : Icons.mic),
                  ),
                ],
              )
            : FutureBuilder<Activity>(
                future: _activityFuture,
                builder: (context, snapshot) {
                  if (snapshot.connectionState == ConnectionState.waiting) {
                    return const CircularProgressIndicator();
                  } else if (snapshot.hasError) {
                    return Text('Error: ${snapshot.error}');
                  } else if (snapshot.hasData) {
                    final activity = snapshot.data!;
                    return Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Text('Activity: ${activity.text}',
                              style: Theme.of(context).textTheme.titleLarge),
                          const SizedBox(height: 10),
                          Text('Duration: ${activity.durationMinutes} minutes',
                              style: Theme.of(context).textTheme.bodyMedium),
                          const SizedBox(height: 10),
                          Text('Category: ${activity.category}',
                              style: Theme.of(context).textTheme.bodyMedium),
                          const SizedBox(height: 20),
                          ElevatedButton(
                              onPressed: () =>
                                  setState(() => _activityFuture = null),
                              child: const Text('Log Another Activity')),
                        ],
                      ),
                    );
                  } else {
                    return const Text('No activity logged yet.');
                  }
                },
              ),
      ),
    );
  }
}

class ThemeProvider with ChangeNotifier {
  ThemeMode _themeMode = ThemeMode.system;

  ThemeMode get themeMode => _themeMode;

  void toggleTheme() {
    _themeMode = _themeMode == ThemeMode.light ? ThemeMode.dark : ThemeMode.light;
    notifyListeners();
  }
}



import 'dart:convert';
import 'package:http/http.dart' as http;
import 'activity_model.dart';

class ApiService {
  static const String _baseUrl = 'http://127.0.0.1:5000'; // Replace with your API base URL

  Future<Activity> uploadVoice(String filePath) async {
    // Simulate API call
    await Future.delayed(const Duration(seconds: 2));
    final dummyResponse = {
      "text": "I read a book for 30 minutes",
      "duration_minutes": 30,
      "category": "Reading"
    };
    return Activity.fromJson(dummyResponse);
  }
}

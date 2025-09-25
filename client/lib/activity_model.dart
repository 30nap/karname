
class Activity {
  final String text;
  final int durationMinutes;
  final String category;

  Activity({
    required this.text,
    required this.durationMinutes,
    required this.category,
  });

  factory Activity.fromJson(Map<String, dynamic> json) {
    return Activity(
      text: json['text'],
      durationMinutes: json['duration_minutes'],
      category: json['category'],
    );
  }
}

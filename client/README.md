# Karname

## Overview

This Flutter application allows users to log their activities by recording their voice. The app transcribes the voice recording, extracts the activity, duration, and category, and displays it to the user. It also provides a reports section to view weekly and monthly activity summaries.

## Features

*   **Voice Recording:** Users can record their voice to describe an activity.
*   **Activity Logging:** The app processes the voice recording to log the activity, duration, and category.
*   **Theme Toggle:** Users can switch between light and dark themes.
*   **Navigation:** A bottom navigation bar allows switching between the home and reports screens.
*   **Reports:** A dedicated screen to display weekly and monthly activity reports.

## Style and Design

*   **Theming:** The app uses Material 3 design with a custom color scheme and typography.
    *   **Color Scheme:** Based on `Colors.deepPurple`.
    *   **Typography:** Uses Google Fonts (`Oswald`, `Roboto`, `Open Sans`).
*   **UI Components:**
    *   `FloatingActionButton` for recording.
    *   `BottomNavigationBar` for navigation.
    *   `FutureBuilder` to handle asynchronous operations.

## Project Structure

*   `lib/main.dart`: Main application entry point, theme setup, and navigation.
*   `lib/home_screen.dart`: Home screen with voice recording functionality.
*   `lib/reports_screen.dart`: Screen to display activity reports.
*   `lib/activity_model.dart`: Data model for activities.
*   `lib/api_service.dart`: Service to handle API calls.

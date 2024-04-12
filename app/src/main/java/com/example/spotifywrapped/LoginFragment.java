package com.example.spotifywrapped;

import static com.example.spotifywrapped.Utils.unblock;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavAction;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;

import com.example.spotifywrapped.databinding.FragmentLoginBinding;
import com.example.spotifywrapped.entities.User;
import com.example.spotifywrapped.spotify.Spotify;
import com.example.spotifywrapped.spotify.Timeframe;
import com.mohamedabulgasem.loadingoverlay.LoadingAnimation;
import com.mohamedabulgasem.loadingoverlay.LoadingOverlay;

import java.util.concurrent.atomic.AtomicInteger;

public class LoginFragment extends Fragment {
    private static final String TAG = "LoginFragment";
    private FragmentLoginBinding binding;
    private Spotify spotify;
    private AppDatabase db;
    private LoadingOverlay loadingOverlay;
    private LoginViewModel viewModel;

    public static LoginFragment newInstance() {
        return new LoginFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        loadingOverlay = LoadingOverlay.Companion.with(requireActivity(), LoadingAnimation.BuiltinAnimations.getLOADING_SPINNER(), 0.5f, false, null, null, null);
        viewModel = new ViewModelProvider(this).get(LoginViewModel.class);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MainActivity activity = (MainActivity) requireActivity();
        spotify = activity.getSpotify();
        db = activity.getDb();

        viewModel.getLoading().observe(getViewLifecycleOwner(), (loading) -> {
            if (loading) {
                loadingOverlay.show();
            } else {
                loadingOverlay.dismiss();
            }
        });

        unblock(() -> {
            if (getActivity() == null) {
                Log.i(TAG, "null activity");
                return;
            }

            Log.i(TAG, "onViewCreated: ");
            if (db.userDao().getAll().isEmpty()) {
                // No user, create account
                viewModel.setLoading(false);
                setupCreate();
            } else {
                // User found, logging in
                viewModel.setLoading(false);
                setupLogin();
            }
        });

        Spotify spotify = activity.getSpotify();

        spotify.setOnAuthResponse((token) -> {
            Log.i(TAG, "onCreateView: Spotify done");
            spotify.getTopTracks(requireActivity(), Timeframe.ONE_YEAR, (res) -> {
                Log.i(TAG, "onCreateView: " + res.get());
            });
        });
    }

    private void setupLogin() {
        if (binding == null) {
            Log.e(TAG, "Setting up login on removed fragment.");
            return;
        }

        requireActivity().runOnUiThread(() -> {
            if (binding != null) {
                binding.createFirstNameContainer.setVisibility(View.GONE);
                binding.createLastNameContainer.setVisibility(View.GONE);
            }
        });

        spotify.setOnAuthResponse((token) -> {
            loadingOverlay.dismiss();
            if (token.isPresent()) {
                // Authed successfully

                // TODO: Navigate to main screen & other stuff
                Log.i(TAG, "Successfully logged in with existing account!");
                unblock(() -> {
                    User user = db.userDao().getAll().get(0);
                    requireActivity().runOnUiThread(() -> {
                        NavDirections navigation = LoginFragmentDirections.actionLoginFragmentToMainFragment(user);
                        Navigation.findNavController(getView()).navigate(navigation);
                    });
                });
            } else {
                // Failed to auth
                showError("Failed to authenticate with Spotify!");
            }
        });

        binding.loginButton.setOnClickListener((e) -> {
            String username = binding.createUsername.getText().toString().trim();
            String password = binding.createPassword.getText().toString().trim();

            if (username.isEmpty() || password.isEmpty()) {
                showError("Username and password cannot be empty.");
                return;
            }

            loadingOverlay.show();
            unblock(() -> {
                try {
                    User user = db.userDao().findByUsername(username);

                    if (user == null || !password.equals(user.password)) {
                        // User doesn't exist
                        showError("Incorrect username or password.");
                        loadingOverlay.dismiss();
                        return;
                    }

                    spotify.getToken(requireActivity());
                } finally {
                    loadingOverlay.dismiss();
                }
            });
        });
    }

    private void setupCreate() {
        requireActivity().runOnUiThread(() -> {
            if (binding != null) {
                binding.createFirstNameContainer.setVisibility(View.VISIBLE);
                binding.createLastNameContainer.setVisibility(View.VISIBLE);
            }
        });
        // Holds ID of created user for deleting in failed cases
        AtomicInteger id = new AtomicInteger(-1);

        spotify.setOnAuthResponse((token) -> {
            String username = binding.createUsername.getText().toString().trim();
            loadingOverlay.dismiss();
            if (token.isPresent()) {
                // Authed & created account successfully

                Log.i(TAG, "Successfully created a new account!");
                unblock(() -> {
                    User user = db.userDao().getAll().get(0);
                    requireActivity().runOnUiThread(() -> {
                        NavDirections navigation = LoginFragmentDirections.actionLoginFragmentToMainFragment(user);
                        Navigation.findNavController(getView()).navigate(navigation);
                    });
                });
            } else {
                // Failed to auth, remove user with this username
                if (id.get() != -1) {
                    User toDelete = new User();
                    toDelete.uid = id.get();
                    db.userDao().delete(toDelete);
                }
                showError("Failed to authenticate with Spotify!");
            }
        });

        binding.loginButton.setOnClickListener((e) -> {
            String firstName = binding.createFirstName.getText().toString().trim();
            String lastName = binding.createLastName.getText().toString().trim();
            String username = binding.createUsername.getText().toString().trim();
            String password = binding.createPassword.getText().toString().trim();

            if (firstName.isEmpty() || lastName.isEmpty() || username.isEmpty() || password.isEmpty()) {
                showError("Fields cannot be empty.");
                return;
            }

            loadingOverlay.show();
            unblock(() -> {
                try {
                    User existingUser = db.userDao().findByUsername(username);

                    if (existingUser != null) {
                        // User already exists (SHOULD be impossible)
                        showError("Username already in use.");
                        loadingOverlay.dismiss();
                        return;
                    }

                    User user = new User();
                    user.firstName = firstName;
                    user.lastName = lastName;
                    user.username = username;
                    user.password = password;
                    db.userDao().insert(user);
                    id.set(db.userDao().findByUsername(username).uid);

                    spotify.getToken(requireActivity());
                } finally {
                    loadingOverlay.dismiss();
                }
            });
        });
    }

    private void showError(String message) {
        requireActivity().runOnUiThread(() -> {
            Toast.makeText(requireActivity(), message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        spotify = null;
        loadingOverlay = null;
    }
}
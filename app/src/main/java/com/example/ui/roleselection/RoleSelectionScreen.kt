package com.example.ui.roleselection

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.UserRole
import com.example.data.repository.CampusRideRepository

@Composable
fun RoleSelectionScreen(
    repository: CampusRideRepository,
    onSelectRole: (UserRole) -> Unit
) {
    var activeDialogRole by remember { mutableStateOf<UserRole?>(null) }
    var inputCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedDriverCartId by remember { mutableStateOf(repository.selectedDriverCartId.value) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Tesseract Dynamics Sacred Geometry Brand Emblem
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFD4AF37), Color(0xFFAA8C2C), Color(0xFFE5C158))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = com.example.R.drawable.img_tesseract_icon),
                contentDescription = "Tesseract Dynamics Emblem",
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Campus Ride",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "IIIT Bhagalpur Executive Transport",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(36.dp))

        Text(
            text = "Select Portal",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Role selection is permanent for this installed application.",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Student Role Card
        RoleCard(
            title = "Student Portal",
            subtitle = "Request golf cart pickup near IIIT Bhagalpur main gate.",
            icon = Icons.Default.School,
            badgeText = "Open Access",
            onClick = { onSelectRole(UserRole.STUDENT) }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Faculty Role Card
        RoleCard(
            title = "Faculty Portal",
            subtitle = "Call golf cart to designated campus pickup points.",
            icon = Icons.Default.Person,
            badgeText = "Passcode Required",
            onClick = {
                inputCode = ""
                errorMessage = null
                activeDialogRole = UserRole.FACULTY
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Driver Role Card
        RoleCard(
            title = "Driver Terminal",
            subtitle = "Manage live pickup queue and monitor golf cart telemetry.",
            icon = Icons.Default.Navigation,
            badgeText = "Passcode Required",
            onClick = {
                inputCode = ""
                errorMessage = null
                activeDialogRole = UserRole.DRIVER
            }
        )
    }

    // Passcode Verification Modal
    activeDialogRole?.let { role ->
        val titleText = if (role == UserRole.FACULTY) "Faculty Security Passcode" else "Driver Terminal Passcode"

        AlertDialog(
            onDismissRequest = { activeDialogRole = null },
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = titleText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Authorized access required for ${role.displayName} portal.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = {
                            inputCode = it
                            errorMessage = null
                        },
                        label = { Text("Security Passcode") },
                        placeholder = { Text("Enter authorized passcode") },
                        singleLine = true,
                        isError = errorMessage != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    if (role == UserRole.DRIVER) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "ASSIGNED VEHICLE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isC1 = selectedDriverCartId == "cart_1"
                            Surface(
                                onClick = { selectedDriverCartId = "cart_1" },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isC1) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isC1) 2.dp else 1.dp,
                                    if (isC1) Color(0xFF16A34A) else Color(0xFFCBD5E1)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (isC1) Color(0xFF16A34A) else Color(0xFF94A3B8))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Cart 1",
                                        fontWeight = if (isC1) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isC1) Color(0xFF15803D) else Color(0xFF475569),
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            val isC2 = selectedDriverCartId == "cart_2"
                            Surface(
                                onClick = { selectedDriverCartId = "cart_2" },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isC2) Color(0xFFDBEAFE) else Color(0xFFF1F5F9),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isC2) 2.dp else 1.dp,
                                    if (isC2) Color(0xFF2563EB) else Color(0xFFCBD5E1)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (isC2) Color(0xFF2563EB) else Color(0xFF94A3B8))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Cart 2",
                                        fontWeight = if (isC2) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isC2) Color(0xFF1D4ED8) else Color(0xFF475569),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    errorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val isValid = if (role == UserRole.FACULTY) {
                            repository.verifyFacultyAccessCode(inputCode)
                        } else {
                            repository.verifyDriverAccessCode(inputCode)
                        }

                        if (isValid) {
                            if (role == UserRole.DRIVER) {
                                repository.setSelectedDriverCartId(selectedDriverCartId)
                            }
                            activeDialogRole = null
                            onSelectRole(role)
                        } else {
                            errorMessage = "Incorrect passcode. Please try again."
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Authenticate", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialogRole = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun RoleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badgeText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Select",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

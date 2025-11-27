package org.example.redisvssyncache;

import org.example.redisvssyncache.models.User;
import org.example.redisvssyncache.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisVsSynCacheApplicationTests {

    @Autowired
    private UserService userService;

    Set<Long> createUsers(int userCount) {
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < userCount; i++) {
            User user = new User();
            user.setUsername("user" + i);
            user.setEmail("user" + i + "@example.com");
            User createdUser = userService.createUser(user);
            assertThat(createdUser).isNotNull();
            assertThat(createdUser.getId()).isNotNull();
            ids.add(createdUser.getId());
        }
        return ids;
    }

    void readUsersMultipleTimes(int count, Set<Long> userIds) {
        for (int i = 0; i < count; i++) {
            for (Long userId : userIds) {
                userService.getUserById(userId);
            }
        }


    }

    void updateUsers(Set<Long> userIds) {
        for (Long userId : userIds) {
            User user = new User();
            user.setUsername("new_user" + userId);
            user.setEmail("new_user" + userId + "@example.com");
            User updatedUser = userService.updateUser(userId, user);
            assertThat(updatedUser).isNotNull();
            assertThat(updatedUser.getUsername()).isEqualTo("new_user" + userId);
            assertThat(updatedUser.getEmail()).isEqualTo("new_user" + userId + "@example.com");
        }
    }

    void deleteUsers(Set<Long> userIds) {
        for (Long userId : userIds) {
            var deleted = userService.deleteUser(userId);
            assertThat(deleted).isNull();
        }
    }

    @Test
    void testUserCrudWithCache() {

        var userIds = createUsers(500);
        readUsersMultipleTimes(500, userIds);

        Instant start = Instant.now();
        readUsersMultipleTimes(500, userIds);
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        System.out.println("Execution time: " + duration.toMillis() + " ms");

        updateUsers(userIds);
        readUsersMultipleTimes(500, userIds);
        deleteUsers(userIds);
        readUsersMultipleTimes(500, userIds);

    }

}

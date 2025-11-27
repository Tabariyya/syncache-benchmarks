package org.example.redisvssyncache.service;

import org.example.redisvssyncache.models.User;
import org.example.redisvssyncache.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;


@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ApplicationContext context;


    // Create user (save + cache)
    @CachePut(value = "users", key = "#result.id")
    public User createUser(User user) {
        return userRepository.save(user);
    }

    // Get user by ID (cacheable)
    @Cacheable(value = "users", key = "#id")
    public User getUserById(Long id) {
        System.out.println("Fetching from DB, not cache!");
        return userRepository.findById(id).orElse(null);
    }

    @CachePut(value = "users", key = "#id")
    public User updateUser(Long id, User updatedUser) {
        // get the proxied instance
        UserService proxy = context.getBean(UserService.class);
        User user = proxy.getUserById(id); // now caching works
        if (user != null) {
            user.setUsername(updatedUser.getUsername());
            user.setEmail(updatedUser.getEmail());
            return userRepository.save(user);
        }
        return null;
    }


    // Delete user (DB + cache null)
    @CachePut(value = "users", key = "#id")
    public User deleteUser(Long id) {
        UserService proxy = context.getBean(UserService.class);
        if (proxy.getUserById(id) != null) {
            userRepository.deleteById(id);
        }
        // Return null so Redis caches null
        return null;
    }

}

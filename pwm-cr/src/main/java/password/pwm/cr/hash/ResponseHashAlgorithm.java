/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2019 The PWM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package password.pwm.cr.hash;

public enum ResponseHashAlgorithm
{
    TEXT( TextHashMachine.class ),
    MD5( TypicalHashMachine.class ),
    SHA1( TypicalHashMachine.class ),
    SHA1_SALT( TypicalHashMachine.class ),
    SHA256_SALT( TypicalHashMachine.class ),
    SHA512_SALT( TypicalHashMachine.class ),
    //    BCRYPT(),
//    SCRYPT(),
    PBKDF2( PBKDF2HashMachine.class ),
    PBKDF2_SHA256( PBKDF2HashMachine.class ),
    PBKDF2_SHA512( PBKDF2HashMachine.class ),;

    private final Class<? extends ResponseHashMachineSpi> implementingClass;

    ResponseHashAlgorithm( final Class<? extends ResponseHashMachineSpi> responseHashMachineSpi )
    {
        this.implementingClass = responseHashMachineSpi;
    }

    public Class<? extends ResponseHashMachineSpi> getImplementingClass( )
    {
        return implementingClass;
    }
}
